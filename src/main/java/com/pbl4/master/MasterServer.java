package com.pbl4.master;

import com.pbl4.computing.graph.Graph;
import com.pbl4.computing.matrix.Matrix;
import com.pbl4.core.model.*;
import com.pbl4.core.network.SocketMessenger;
import com.pbl4.master.aggregate.ResultAggregator;
import com.pbl4.master.scheduler.*;
import com.pbl4.master.split.TaskSplitter;
import com.pbl4.worker.WorkerConfig;
import com.pbl4.worker.WorkerNode;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class MasterServer {
    private final int port;
    private final String bindHost;
    private final Path dataDirectory;
    private final String jarSha256 = com.pbl4.core.network.RuntimeBuild.SHA256;
    private final String session = UUID.randomUUID().toString();
    private final WorkerRegistry registry;
    private final TaskScheduler scheduler;
    private final JobDispatcher dispatcher;
    private final MasterStateStore stateStore;
    private final LocalWorkerManager localWorkerManager;
    private final ExecutorService asyncJobExecutor;
    private final ExecutorService workerControlPool;
    private final AtomicBoolean running = new AtomicBoolean();
    private ServerSocket serverSocket;
    private ExecutorService connectionThreadPool;
    private long jobTimeoutMs = 300_000;
    public MasterServer(int port) { this(port, "0.0.0.0"); }
    public MasterServer(int port, String bindHost) {
        this(port, bindHost, Path.of(System.getProperty("pbl4.dataDir", "data"), "master-" + port));
    }
    public MasterServer(int port, String bindHost, Path dataDirectory) {
        this.port = port; this.bindHost = bindHost;
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        try { stateStore = new MasterStateStore(this.dataDirectory); }
        catch (IOException e) { throw new IllegalStateException("Cannot open durable Master state: " + e.getMessage(), e); }
        WorkerRegistry initializedRegistry = null;
        JobDispatcher initializedDispatcher = null;
        ExecutorService initializedAsyncJobExecutor = null;
        ExecutorService initializedControlPool = null;
        try {
            initializedRegistry = new WorkerRegistry();
            initializedRegistry.loadAliases(stateStore.aliases());
            TaskScheduler initializedScheduler = new TaskScheduler(initializedRegistry);
            initializedDispatcher = new JobDispatcher(session, initializedScheduler);
            initializedAsyncJobExecutor = Executors.newCachedThreadPool();
            initializedControlPool = Executors.newFixedThreadPool(8, runnable -> {
                Thread t = new Thread(runnable, "master-worker-control");
                t.setDaemon(true);
                return t;
            });
            registry = initializedRegistry;
            scheduler = initializedScheduler;
            dispatcher = initializedDispatcher;
            asyncJobExecutor = initializedAsyncJobExecutor;
            workerControlPool = initializedControlPool;
            localWorkerManager = new LocalWorkerManager(this, this.dataDirectory.resolve("managed-workers"));

            dispatcher.setResultCommitter(result -> {
                if (result.isSuccess() && stateStore.hasJob(result.getJobId())) stateStore.recordResult(result.getJobId(), result);
            });
            dispatcher.setAttemptRecorder(new JobDispatcher.AttemptRecorder() {
                @Override public void record(Task task, WorkerInfo worker) throws IOException {
                    if (stateStore.hasJob(task.getJobId())) stateStore.recordAttempt(task.getJobId(), task.getTaskId(),
                        task.attemptId, worker.getWorkerId(), worker.sessionId, session, System.currentTimeMillis());
                }
                @Override public void transition(Task task, WorkerInfo worker, String attemptId, String state, String reason) throws IOException {
                    if (stateStore.hasJob(task.getJobId())) stateStore.updateAttemptState(task.getJobId(), task.getTaskId(),
                        attemptId, state, System.currentTimeMillis(), reason);
                }
            });
            restoreAsyncJobs();
            registry.setOnWorkerFailedListener(dispatcher::workerFailed);
        } catch (RuntimeException | Error startupFailure) {
            if (initializedControlPool != null) initializedControlPool.shutdownNow();
            if (initializedAsyncJobExecutor != null) initializedAsyncJobExecutor.shutdownNow();
            if (initializedDispatcher != null) initializedDispatcher.close();
            if (initializedRegistry != null) initializedRegistry.shutdown();
            try { stateStore.close(); }
            catch (IOException closeError) { startupFailure.addSuppressed(closeError); }
            throw startupFailure;
        }
    }

    private void restoreAsyncJobs() {
        for (MasterStateStore.StoredJob saved : stateStore.jobs()) {
            if (saved.request == null) continue;
            Map<String, Object> request = com.pbl4.core.network.JsonUtils.getMapper().convertValue(saved.request,
                new com.fasterxml.jackson.core.type.TypeReference<>() {});
            MatrixJobPlan plan = planFromRequest(request);
            List<Task> tasks = plan.tasks(saved.jobId, Math.max(1, saved.totalTasks));
            String title = "Tinh toan ma tran phan tan [" + plan.operation + "]: A [" + plan.a.getRows() + " x " + plan.a.getCols() + "]";
            AsyncJobRecord rec = new AsyncJobRecord(saved.jobId, title, plan.operation, saved.totalTasks, saved.startedAt, saved.deadlineAt);
            rec.request = request; rec.persistedResults.putAll(saved.results); rec.completedTasks = saved.results.size();
            rec.totalUnits = tasks.stream().mapToLong(t -> Math.max(1, t.workUnits)).sum();
            rec.completedUnits = tasks.stream().filter(t -> saved.results.containsKey(t.getTaskId())).mapToLong(t -> Math.max(1, t.workUnits)).sum();
            rec.progressPercent = rec.totalUnits == 0 ? 0 : rec.completedUnits * 100.0 / rec.totalUnits;
            saved.results.values().forEach(result -> rec.workerCompletedTasks.merge(result.getWorkerId(), 1L, Long::sum));
            if ("COMPLETED".equals(saved.state) && saved.resultSummary != null) {
                rec.status = JobStatus.COMPLETED;
                rec.resultSummary = com.pbl4.core.network.JsonUtils.getMapper().convertValue(saved.resultSummary,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
                Job done = new Job(saved.jobId, title, tasks.get(0).getTaskType()); done.setTasks(tasks);
                saved.results.values().forEach(done::addResult); rec.result = plan.aggregate(done);
                if (rec.result instanceof Matrix matrix) rec.resultMatrix = matrix;
                rec.progressPercent = 100; rec.completedTasks = rec.totalTasks;
                rec.endTime = saved.startedAt + saved.resultSummary.path("distributedTimeMs").asLong();
                rec.durationMs = Math.max(0, rec.endTime - rec.startTime);
            } else if ("FAILED".equals(saved.state) || "CANCELLED".equals(saved.state)) {
                rec.status = "CANCELLED".equals(saved.state) ? JobStatus.CANCELLED : JobStatus.FAILED;
                rec.error = saved.error; rec.endTime = System.currentTimeMillis(); rec.durationMs = rec.endTime - rec.startTime;
            } else {
                rec.status = JobStatus.RECOVERING; rec.totalTasks = tasks.size();
            }
            asyncJobs.put(saved.jobId, rec);
        }
    }

    @SuppressWarnings("unchecked")
    private MatrixJobPlan planFromRequest(Map<String, Object> request) {
        Map<String, Object> genA = (Map<String, Object>) request.get("genA"), genB = (Map<String, Object>) request.get("genB");
        double[][] inputA = request.get("matrixA") == null ? null : com.pbl4.core.network.JsonUtils.getMapper().convertValue(request.get("matrixA"), double[][].class);
        double[][] inputB = request.get("matrixB") == null ? null : com.pbl4.core.network.JsonUtils.getMapper().convertValue(request.get("matrixB"), double[][].class);
        return new MatrixJobPlan((String) request.get("operation"), ((Number) request.get("rowsA")).intValue(),
            ((Number) request.get("colsA")).intValue(), ((Number) request.getOrDefault("rowsB", 1)).intValue(),
            ((Number) request.getOrDefault("colsB", 1)).intValue(), genA, genB,
            ((Number) request.getOrDefault("scalar", 2.0)).doubleValue(), inputA, inputB);
    }
    public void setJobTimeoutMs(long value) {
        if (value <= 0) throw new IllegalArgumentException("job timeout must be positive"); jobTimeoutMs = value;
    }
    public void setAttemptTimeoutMs(long value) { dispatcher.setAttemptTimeoutMs(value); }
    public void setAttemptHardTimeoutMs(long value) { dispatcher.setAttemptHardTimeoutMs(value); }
    public void setQuarantineRestartAfterMs(long value) { dispatcher.setQuarantineRestartAfterMs(value); }
    public synchronized void start() throws IOException {
        if (running.get()) return;
        serverSocket = new ServerSocket();
        try { serverSocket.bind(new InetSocketAddress(bindHost, port)); }
        catch (IOException | RuntimeException e) { stop(); throw e; }
        connectionThreadPool = Executors.newCachedThreadPool(); running.set(true);
        Thread listener = new Thread(() -> {
            while (running.get()) {
                try { Socket socket = serverSocket.accept(); socket.setSoTimeout(5000); connectionThreadPool.submit(() -> handle(socket)); }
                catch (IOException | RejectedExecutionException e) { if (running.get()) System.err.println(e.getMessage()); }
            }
        }, "Master-listener"); listener.setDaemon(true); listener.start();
        System.out.println("[Master] Listening " + bindHost + ":" + serverSocket.getLocalPort());
        asyncJobs.values().stream().filter(rec -> rec.status == JobStatus.RUNNING)
            .forEach(rec -> asyncJobExecutor.submit(() -> resumeAsyncMatrixJob(rec)));
    }
    private Message reply(MessageType type, String text) {
        Message result = new Message(type, "MASTER"); result.masterSession = session; result.setContent(text); return result;
    }
    private void handle(Socket socket) {
        try (socket;
             var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            Message request = SocketMessenger.receiveMessage(reader); if (request == null) return;
            Message response;
            if (request.protocolVersion != Message.CURRENT_VERSION) response = reply(MessageType.REGISTER_REJECTED, "Protocol v4 required; update the JAR on both laptops and restart Master/Workers");
            else switch (request.getType()) {
                case REGISTER_WORKER -> response = register(request);
                case HEARTBEAT -> {
                    boolean known = session.equals(request.masterSession) && registry.heartbeat(request.getSenderId(), request.workerSession, request.metrics);
                    WorkerInfo w = registry.getWorker(request.getSenderId());
                    response = reply(known ? MessageType.HEARTBEAT_ACK : MessageType.REGISTER_REQUIRED,
                        known && w.paused ? "PAUSED" : "READY");
                }
                case TASK_RESULT -> {
                    JobDispatcher.ResultDisposition disposition = dispatcher.result(request.getTaskResult());
                    WorkerInfo w = registry.getWorker(request.getSenderId());
                    if (w != null && Objects.equals(w.sessionId, request.workerSession) && request.metrics != null) w.metrics = request.metrics;
                    response = disposition == JobDispatcher.ResultDisposition.RETRY
                        ? reply(MessageType.RESULT_RETRY, "Master could not persist result; retry")
                        : reply(MessageType.RESULT_ACK, disposition == JobDispatcher.ResultDisposition.COMMITTED ? "Durably committed" : "Stale or duplicate ignored");
                }
                case TASK_PROGRESS -> {
                    if (session.equals(request.masterSession)) {
                        String[] parts = Optional.ofNullable(request.getContent()).orElse("").split("\\|", 3);
                        if (parts.length == 3) try {
                            dispatcher.reportProgress(request.getSenderId(), request.workerSession, request.masterSession,
                                parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                        } catch (NumberFormatException ignored) { }
                    }
                    response = reply(MessageType.HEARTBEAT_ACK, "Progress received");
                }
                default -> response = reply(MessageType.REGISTER_REJECTED, "Unsupported message");
            }
            SocketMessenger.sendMessage(writer, response);
            if (response.getType() == MessageType.REGISTER_ACK) {
                WorkerInfo w = request.getWorkerInfo();
                System.out.printf("[Master] REGISTERED %s | laptop=%s (%s) | %s:%d%n", w.getWorkerId(), w.machineId, w.machineName, w.getHost(), w.getPort());
            }
        } catch (Exception e) { if (running.get()) System.err.println("[Master connection] " + e.getMessage()); }
    }
    private Message register(Message request) {
        try {
            WorkerInfo info = request.getWorkerInfo();
            if (info == null || info.sessionId == null || info.getWorkerId() == null || info.getWorkerId().isBlank()
                || info.getHost() == null || info.getCapacity() < 1 || info.getPort() < 1 || info.getPort() > 65535
                || info.machineId == null || info.machineId.isBlank() || info.machineName == null || info.machineName.isBlank() || info.hostParallelism < 1)
                throw new IllegalArgumentException("Invalid Worker registration");
            if (info.processGroupId == null || info.processGroupId.isBlank()) info.processGroupId = info.machineId;
            if (info.groupParallelism < 1) info.groupParallelism = info.hostParallelism;
            if (info.groupParallelism > info.hostParallelism) throw new IllegalArgumentException("Worker group budget exceeds laptop budget");
            Message ping = new Message(MessageType.PING, "MASTER");
            Message pong = SocketMessenger.sendAndReceive(info.getHost(), info.getPort(), ping, 2000);
            if (pong == null || pong.protocolVersion != Message.CURRENT_VERSION || pong.getType() != MessageType.PONG
                || !Objects.equals(info.sessionId, pong.workerSession)) throw new IOException("Reverse connection/session check failed");
            registry.register(info);
            return reply(MessageType.REGISTER_ACK, registry.getWorker(info.getWorkerId()).paused ? "PAUSED" : "READY");
        } catch (Exception e) { return reply(MessageType.REGISTER_REJECTED, e.getMessage()); }
    }
    public boolean executeJob(Job job, long timeoutMs) { return dispatcher.execute(job, timeoutMs); }
    private int partitions() {
        return partitionsForWorkload(0L, Integer.MAX_VALUE);
    }

    private int partitionsForPlan(MatrixJobPlan plan) {
        return partitionsForWorkload(plan.maxCells(), plan.a.getRows());
    }

    private int partitionsForWorkload(long maxCells, int maxRows) {
        long effectiveCap = Math.max(1L, scheduler.effectiveCapacity());
        long workerDriven = 2L * effectiveCap;
        long sizeDriven = maxCells > 1_000_000L ? Math.max(1L, (maxCells + 249_999L) / 250_000L) : 1L;
        long target = Math.max(workerDriven, sizeDriven);
        return (int) Math.min(Math.max(1, maxRows), Math.min(1024L, target));
    }
    private <T> T aggregate(Job job, Supplier<T> calculation) {
        try {
            T value = calculation.get(); job.setStatus(JobStatus.COMPLETED); job.setEndTime(System.currentTimeMillis()); return value;
        } catch (RuntimeException e) { job.setStatus(JobStatus.FAILED); throw e; }
        finally { job.getResults().clear(); }
    }
    // ==========================================
    // CAC HAM XU LY NGHIEP VU CAP CAO (API)
    // ==========================================

    /**
     * Vi du khoi dau: Tinh toan song song bieu thuc (VD: 1+2 va 3*2 -> Tong hop = 9)
     */
    public Double runArithmeticStarter(String expr1, String expr2, String topOp) {
        String jobId = "arithmetic-" + UUID.randomUUID().toString();
        Job job = new Job(jobId, "Bieu thuc song song: (" + expr1 + ") " + topOp + " (" + expr2 + ")", TaskType.ARITHMETIC);
        
        List<Task> tasks = TaskSplitter.splitArithmetic(jobId, expr1, expr2);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB KHOI DAU: (%s) %s (%s) ===%n", expr1, topOp, expr2);
        boolean ok = executeJob(job, jobTimeoutMs);
        if (ok) {
            double res = aggregate(job, () -> ResultAggregator.aggregateArithmetic(job, topOp));
            System.out.printf(">>> KET QUA TONG HOP: %s = %.2f (Thoi gian: %d ms)%n",
                    job.getTitle(), res, job.getDurationMs());
            return res;
        } else {
            System.err.println(">>> THAT BAI KHI THUC THI JOB!");
            return null;
        }
    }

    /**
     * Phep cong hoac tru ma tran phan tan A +/- B
     */
    public Matrix runMatrixAddOrSub(Matrix A, Matrix B, boolean isAdd) {
        TaskType type = isAdd ? TaskType.MATRIX_ADD : TaskType.MATRIX_SUB;
        String opName = isAdd ? "Cong" : "Tru";
        String jobId = "mat-" + (isAdd ? "add" : "sub") + "-" + UUID.randomUUID().toString();

        int partitionCount = partitions();
        Job job = new Job(jobId, String.format("%s ma tran [%d x %d]", opName, A.getRows(), A.getCols()), type);
        List<Task> tasks = TaskSplitter.splitMatrixAddSub(jobId, A, B, type, partitionCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB %s MA TRAN [%d x %d] CHIA THANH %d TASKS ===%n",
                opName.toUpperCase(), A.getRows(), A.getCols(), tasks.size());

        boolean ok = executeJob(job, jobTimeoutMs);
        if (ok) {
            Matrix res = aggregate(job, () -> ResultAggregator.aggregateMatrix(job, A.getRows(), A.getCols()));
            System.out.printf(">>> HOAN THANH %s MA TRAN TRONG %d ms!%n", opName, job.getDurationMs());
            return res;
        }
        return null;
    }

    /**
     * Phep chia lay du (%) 2 ma tran phan tan C = A % B
     */
    public Matrix runMatrixModulo(Matrix A, Matrix B) {
        String jobId = "mat-mod-" + UUID.randomUUID().toString();
        int partitionCount = partitions();

        Job job = new Job(jobId, String.format("Chia lay du (%%) ma tran [%d x %d]", A.getRows(), A.getCols()), TaskType.MATRIX_MOD);
        List<Task> tasks = TaskSplitter.splitMatrixAddSub(jobId, A, B, TaskType.MATRIX_MOD, partitionCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB CHIA LAY DU (%%) MA TRAN [%d x %d] CHIA THANH %d TASKS ===%n",
                A.getRows(), A.getCols(), tasks.size());

        boolean ok = executeJob(job, jobTimeoutMs);
        if (ok) {
            Matrix res = aggregate(job, () -> ResultAggregator.aggregateMatrix(job, A.getRows(), A.getCols()));
            System.out.printf(">>> HOAN THANH CHIA LAY DU (%%) MA TRAN TRONG %d ms!%n", job.getDurationMs());
            return res;
        }
        return null;
    }

    /**
     * Phep nhan 2 ma tran phan tan C = A * B
     */
    public Matrix runMatrixMultiplication(Matrix A, Matrix B) {
        String jobId = "mat-mul-" + UUID.randomUUID().toString();
        int partitionCount = partitions();

        Job job = new Job(jobId, String.format("Nhan ma tran [%d x %d] * [%d x %d]",
                A.getRows(), A.getCols(), B.getRows(), B.getCols()), TaskType.MATRIX_MULTIPLY);
        List<Task> tasks = TaskSplitter.splitMatrixMultiply(jobId, A, B, partitionCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB NHAN MA TRAN [%d x %d] * [%d x %d] CHIA THANH %d TASKS ===%n",
                A.getRows(), A.getCols(), B.getRows(), B.getCols(), tasks.size());

        boolean ok = executeJob(job, jobTimeoutMs);
        if (ok) {
            Matrix res = aggregate(job, () -> ResultAggregator.aggregateMatrix(job, A.getRows(), B.getCols()));
            System.out.printf(">>> HOAN THANH NHAN MA TRAN TRONG %d ms!%n", job.getDurationMs());
            return res;
        }
        return null;
    }

    /**
     * Tinh dinh thuc ma tran phan tan bang cach chia cac so hang cofactor.
     */
    public Double runMatrixDeterminant(Matrix A) {
        if (A.getRows() != A.getCols()) {
            throw new IllegalArgumentException("Dinh thuc chi tinh duoc cho ma tran vuong!");
        }

        String jobId = "mat-det-" + UUID.randomUUID().toString();
        int partitionCount = partitions();

        Job job = new Job(jobId, String.format("Tinh dinh thuc ma tran [%d x %d]",
                A.getRows(), A.getCols()), TaskType.MATRIX_DETERMINANT);
        List<Task> tasks = TaskSplitter.splitMatrixDeterminant(jobId, A, partitionCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB TINH DINH THUC MA TRAN [%d x %d] CHIA THANH %d TASKS ===%n",
                A.getRows(), A.getCols(), tasks.size());

        boolean ok = executeJob(job, jobTimeoutMs);
        if (ok) {
            double determinant = aggregate(job, () -> ResultAggregator.aggregateMatrixDeterminant(job));
            System.out.printf(">>> HOAN THANH TINH DINH THUC TRONG %d ms!%n", job.getDurationMs());
            return determinant;
        }
        return null;
    }

    /**
     * Tinh hang ma tran phan tan bang cach rut gon tung lat cat dong tren Worker.
     */
    public Integer runMatrixRank(Matrix A) {
        String jobId = "mat-rank-" + UUID.randomUUID().toString();
        int partitionCount = partitions();

        Job job = new Job(jobId, String.format("Tinh hang ma tran [%d x %d]",
                A.getRows(), A.getCols()), TaskType.MATRIX_RANK);
        List<Task> tasks = TaskSplitter.splitMatrixRank(jobId, A, partitionCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB TINH HANG MA TRAN [%d x %d] CHIA THANH %d TASKS ===%n",
                A.getRows(), A.getCols(), tasks.size());

        boolean ok = executeJob(job, jobTimeoutMs);
        if (ok) {
            int rank = aggregate(job, () -> ResultAggregator.aggregateMatrixRank(job, A.getCols()));
            System.out.printf(">>> HOAN THANH TINH HANG MA TRAN TRONG %d ms!%n", job.getDurationMs());
            return rank;
        }
        return null;
    }

    /**
     * Phep chuyen vi ma tran phan tan A -> A^T
     */
    public Matrix runMatrixTranspose(Matrix A) {
        String jobId = "mat-trans-" + UUID.randomUUID().toString();
        int partitionCount = partitions();

        Job job = new Job(jobId, String.format("Chuyen vi ma tran [%d x %d] -> [%d x %d]",
                A.getRows(), A.getCols(), A.getCols(), A.getRows()), TaskType.MATRIX_TRANSPOSE);
        List<Task> tasks = TaskSplitter.splitMatrixTranspose(jobId, A, partitionCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB CHUYEN VI MA TRAN [%d x %d] CHIA THANH %d TASKS ===%n",
                A.getRows(), A.getCols(), tasks.size());

        boolean ok = executeJob(job, jobTimeoutMs);
        if (ok) {
            Matrix res = aggregate(job, () -> ResultAggregator.aggregateMatrixTranspose(job, A.getRows(), A.getCols()));
            System.out.printf(">>> HOAN THANH CHUYEN VI MA TRAN TRONG %d ms!%n", job.getDurationMs());
            return res;
        }
        return null;
    }

    /**
     * Phep nhan ma tran voi so vo huong phan tan C = A * scalar
     */
    public Matrix runMatrixScalarMultiplication(Matrix A, double scalar) {
        String jobId = "mat-scalar-" + UUID.randomUUID().toString();
        int partitionCount = partitions();

        Job job = new Job(jobId, String.format("Nhan ma tran [%d x %d] voi so vo huong %.2f",
                A.getRows(), A.getCols(), scalar), TaskType.MATRIX_SCALAR_MUL);
        List<Task> tasks = TaskSplitter.splitMatrixScalarMul(jobId, A, scalar, partitionCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB NHAN VO HUONG MA TRAN [%d x %d] * %.2f CHIA THANH %d TASKS ===%n",
                A.getRows(), A.getCols(), scalar, tasks.size());

        boolean ok = executeJob(job, jobTimeoutMs);
        if (ok) {
            Matrix res = aggregate(job, () -> ResultAggregator.aggregateMatrix(job, A.getRows(), A.getCols()));
            System.out.printf(">>> HOAN THANH NHAN VO HUONG MA TRAN TRONG %d ms!%n", job.getDurationMs());
            return res;
        }
        return null;
    }

    /**
     * Phep nghich dao ma tran phan tan A^-1
     */
    public Matrix runMatrixInverse(Matrix A) {
        if (A.getRows() != A.getCols()) {
            throw new IllegalArgumentException("Nghich dao chi tinh duoc cho ma tran vuong!");
        }

        String jobId = "mat-inv-" + UUID.randomUUID().toString();
        int partitionCount = partitions();

        Job job = new Job(jobId, String.format("Nghich dao ma tran [%d x %d]",
                A.getRows(), A.getCols()), TaskType.MATRIX_INVERSE);
        List<Task> tasks = TaskSplitter.splitMatrixInverse(jobId, A, partitionCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB NGHICH DAO MA TRAN [%d x %d] CHIA THANH %d TASKS ===%n",
                A.getRows(), A.getCols(), tasks.size());

        boolean ok = executeJob(job, jobTimeoutMs);
        if (ok) {
            Matrix res = aggregate(job, () -> ResultAggregator.aggregateMatrix(job, A.getRows(), A.getCols()));
            System.out.printf(">>> HOAN THANH NGHICH DAO MA TRAN TRONG %d ms!%n", job.getDurationMs());
            return res;
        }
        return null;
    }

    /**
     * Phep nhan chuoi nhieu ma tran phan tan M1 * M2 * M3 ...
     */
    public Matrix runMatrixChainMultiplication(List<Matrix> matrices) {
        if (matrices == null || matrices.size() < 2) {
            throw new IllegalArgumentException("Can it nhat 2 ma tran de nhan chuoi!");
        }

        System.out.printf("%n=== BAT DAU NHAN CHUOI %d MA TRAN PHAN TAN ===%n", matrices.size());
        long startTotal = System.currentTimeMillis();

        Matrix current = matrices.get(0);
        for (int i = 1; i < matrices.size(); i++) {
            System.out.printf("--- Giai doan %d: Nhan voi ma tran thu %d [%d x %d] ---%n",
                    i, i + 1, matrices.get(i).getRows(), matrices.get(i).getCols());
            current = runMatrixMultiplication(current, matrices.get(i));
            if (current == null) {
                System.err.println("Nhan chuoi ma tran that bai tai buoc " + i);
                return null;
            }
        }

        long totalTime = System.currentTimeMillis() - startTotal;
        System.out.printf(">>> HOAN TAT TOAN BO CHUOI NHAN TRONG %d ms! Kich thuoc cuoi: [%d x %d]%n",
                totalTime, current.getRows(), current.getCols());
        return current;
    }

    /**
     * Tim duong di ngan nhat do thi phan tan (All-Pairs Shortest Path)
     */
    public double[][] runGraphShortestPath(Graph graph) {
        String jobId = "graph-sp-" + UUID.randomUUID().toString();
        int partitionCount = partitions();

        Job job = new Job(jobId, String.format("Tim duong di ngan nhat do thi (%d dinh)",
                graph.getVertices()), TaskType.GRAPH_SHORTEST_PATH);
        List<Task> tasks = TaskSplitter.splitGraphShortestPath(jobId, graph, partitionCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU TIM DUONG DI NGAN NHAT PHAN TAN (%d DINH) CHIA THANH %d TASKS ===%n",
                graph.getVertices(), tasks.size());

        boolean ok = executeJob(job, jobTimeoutMs);
        if (ok) {
            double[][] dist = aggregate(job, () -> ResultAggregator.aggregateGraphShortestPath(job, graph.getVertices()));
            System.out.printf(">>> HOAN THANH TIM DUONG DI NGAN NHAT TRONG %d ms!%n", job.getDurationMs());
            return dist;
        }
        return null;
    }

    /**
     * Chuyen doi trang thai On/Off cua mot Worker theo chi dinh
     */
    public boolean toggleWorkerState(String workerId) {
        WorkerInfo worker = registry.getWorker(workerId);
        if (worker == null) return false;
        worker.paused = !worker.paused;
        try {
            Message request = Message.setWorkerState(workerId, !worker.paused); request.masterSession = session;
            SocketMessenger.sendAndReceive(worker.getHost(), worker.getPort(), request, 3000);
        } catch (IOException e) { System.err.println("[Master] State saved; heartbeat will synchronize: " + e.getMessage()); }
        return true;
    }

    public boolean renameWorker(String workerId, String displayName) {
        WorkerInfo worker = registry.getWorker(workerId);
        if (worker == null) return false;
        String normalized;
        try {
            normalized = MasterStateStore.validateDisplayName(displayName);
            stateStore.recordWorkerAlias(workerId, normalized);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid worker displayName: " + e.getMessage(), e);
        }
        registry.setAlias(workerId, normalized);
        return true;
    }

    public String resolveWorkerDisplayName(String workerId) {
        if (workerId == null || workerId.isBlank()) return workerId;
        WorkerInfo info = registry.getWorker(workerId);
        if (info != null && info.displayName != null && !info.displayName.isBlank()) return info.displayName;
        String alias = registry.getAlias(workerId);
        return (alias != null && !alias.isBlank()) ? alias : workerId;
    }

    public Map<String, Object> setWorkersState(String action, List<String> workerIds, String machineId) {
        String normalizedAction = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PAUSE", "RESUME", "PAUSE_OTHERS", "RESUME_ALL").contains(normalizedAction)) {
            throw new IllegalArgumentException("action must be PAUSE, RESUME, PAUSE_OTHERS, or RESUME_ALL");
        }
        boolean hasWorkerIds = workerIds != null && workerIds.stream().anyMatch(id -> id != null && !id.isBlank());
        boolean hasMachineId = machineId != null && !machineId.isBlank();
        if (!"RESUME_ALL".equals(normalizedAction) && !hasWorkerIds && !hasMachineId) {
            throw new IllegalArgumentException("workerIds or machineId is required for action " + normalizedAction);
        }

        Set<String> selectedIds = new LinkedHashSet<>();
        if (workerIds != null) {
            for (String wid : workerIds) {
                if (wid != null && !wid.isBlank()) selectedIds.add(wid.strip());
            }
        }
        if (hasMachineId) {
            String targetMachine = machineId.strip();
            for (WorkerInfo w : registry.getAllWorkers()) {
                if (targetMachine.equals(w.machineId)) selectedIds.add(w.getWorkerId());
            }
        }

        List<WorkerInfo> targets = new ArrayList<>();
        List<String> skippedOfflineIds = new ArrayList<>();
        boolean targetPaused;

        if ("PAUSE".equals(normalizedAction) || "RESUME".equals(normalizedAction)) {
            targetPaused = "PAUSE".equals(normalizedAction);
            for (String wid : selectedIds) {
                WorkerInfo w = registry.getWorker(wid);
                if (w == null) continue;
                if (w.getStatus() == WorkerStatus.OFFLINE) skippedOfflineIds.add(wid);
                else targets.add(w);
            }
        } else if ("PAUSE_OTHERS".equals(normalizedAction)) {
            targetPaused = true;
            for (WorkerInfo w : registry.getAllWorkers().stream().sorted(Comparator.comparing(WorkerInfo::getWorkerId)).toList()) {
                if (!selectedIds.contains(w.getWorkerId())) {
                    if (w.getStatus() == WorkerStatus.OFFLINE) skippedOfflineIds.add(w.getWorkerId());
                    else targets.add(w);
                }
            }
        } else {
            targetPaused = false;
            for (WorkerInfo w : registry.getAllWorkers().stream().sorted(Comparator.comparing(WorkerInfo::getWorkerId)).toList()) {
                if (w.getStatus() == WorkerStatus.OFFLINE) skippedOfflineIds.add(w.getWorkerId());
                else targets.add(w);
            }
        }

        List<String> updatedWorkerIds = new ArrayList<>(targets.size());
        List<Future<?>> futures = new ArrayList<>(targets.size());
        for (WorkerInfo w : targets) {
            w.paused = targetPaused;
            updatedWorkerIds.add(w.getWorkerId());
            futures.add(workerControlPool.submit(() -> {
                try {
                    Message req = Message.setWorkerState(w.getWorkerId(), !targetPaused);
                    req.masterSession = session;
                    SocketMessenger.sendAndReceive(w.getHost(), w.getPort(), req, 1000);
                } catch (IOException e) {
                    System.err.println("[Master] State saved for " + w.getWorkerId() + "; heartbeat will synchronize: " + e.getMessage());
                }
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (ExecutionException ignored) { }
        }

        int activeCount = 0;
        int pausedCount = 0;
        for (WorkerInfo w : registry.getAllWorkers()) {
            if (w.getStatus() != WorkerStatus.OFFLINE) {
                if (w.paused) pausedCount++;
                else activeCount++;
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("action", normalizedAction);
        resp.put("updatedWorkerIds", updatedWorkerIds);
        resp.put("skippedOfflineIds", skippedOfflineIds);
        resp.put("activeCount", activeCount);
        resp.put("pausedCount", pausedCount);
        return resp;
    }

    /**
     * Gioi han so luong Worker duoc phep nhan tac vu (phuc vu Benchmark 1 worker vs nhieu worker).
     * Neu count <= 0 hoac >= so worker online: mo khoa (unpause) toan bo.
     * Neu count > 0: chi mo khoa dung count worker dau tien, tam dung (pause) cac worker con lai.
     */
    public synchronized List<String> setActiveWorkerLimit(int count) {
        List<WorkerInfo> online = registry.getAllWorkers().stream()
                .filter(w -> w.getStatus() != WorkerStatus.OFFLINE)
                .sorted(Comparator.comparing(WorkerInfo::getWorkerId))
                .toList();
        if (online.isEmpty()) return List.of();

        List<String> activeIds = new ArrayList<>();
        boolean unpauseAll = (count <= 0 || count >= online.size());

        for (int i = 0; i < online.size(); i++) {
            WorkerInfo w = online.get(i);
            boolean shouldBePaused = !unpauseAll && (i >= count);
            if (w.paused != shouldBePaused) {
                w.paused = shouldBePaused;
                try {
                    Message request = Message.setWorkerState(w.getWorkerId(), !w.paused);
                    request.masterSession = session;
                    SocketMessenger.sendAndReceive(w.getHost(), w.getPort(), request, 2000);
                } catch (IOException ignored) {}
            }
            if (!w.paused) {
                activeIds.add(w.getWorkerId());
            }
        }
        return activeIds;
    }
    public synchronized void stop() {
        running.set(false);
        localWorkerManager.close();
        workerControlPool.shutdownNow();
        dispatcher.close(); registry.shutdown();
        asyncJobExecutor.shutdownNow();
        try { stateStore.close(); } catch (IOException e) { System.err.println("[Master] State-store close failed: " + e.getMessage()); }


        if (connectionThreadPool != null) {
            connectionThreadPool.shutdownNow();
        }
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        System.out.println("[Master Server] Da dung hoat dong an toan.");
    }

    public Path getDataDirectory() { return dataDirectory; }
    public String getBindHost() { return bindHost; }
    public int getActualPort() { ServerSocket s = serverSocket; return s == null ? port : s.getLocalPort(); }
    public boolean isTcpListening() { ServerSocket s = serverSocket; return running.get() && s != null && !s.isClosed() && s.getLocalPort() > 0; }
    public LocalWorkerManager getLocalWorkerManager() { return localWorkerManager; }

    public WorkerRegistry getRegistry() {
        return registry;
    }
    public String getJarSha256() { return jarSha256; }
    public Map<String, Object> getClusterSnapshot() {
        return ClusterSnapshot.capture(this, session, serverSocket == null ? port : serverSocket.getLocalPort(), registry, scheduler, dispatcher);
    }

    public TaskScheduler getScheduler() {
        return scheduler;
    }

    public boolean isRunning() {
        return running.get();
    }

    public JobDispatcher getDispatcher() {
        return dispatcher;
    }

    public static class AsyncJobRecord {
        public final String jobId;
        public final String title;
        public final String operation;
        public final long startTime;
        public final long deadlineAt;
        public volatile long endTime;
        public volatile JobStatus status;
        public int totalTasks;
        public volatile int completedTasks;
        public volatile double progressPercent;
        public long totalUnits;
        public volatile long completedUnits;
        public volatile Map<String, Long> workerCompletedTasks = new LinkedHashMap<>();
        public volatile long durationMs;
        public volatile Object result;
        public volatile Matrix resultMatrix;
        public volatile Map<String, Object> resultSummary;
        public volatile String error;
        public final List<Map<String, Object>> failoverLogs = Collections.synchronizedList(new ArrayList<>());
        public volatile Map<String, String> activeWorkerAssignments = new ConcurrentHashMap<>();
        public volatile Map<String, Object> request = Map.of();
        public final Map<String, TaskResult> persistedResults = new ConcurrentHashMap<>();

        public AsyncJobRecord(String jobId, String title, String operation, int totalTasks) {
            this(jobId, title, operation, totalTasks, System.currentTimeMillis(), 0);
        }
        public AsyncJobRecord(String jobId, String title, String operation, int totalTasks, long startedAt) {
            this(jobId, title, operation, totalTasks, startedAt, 0);
        }
        public AsyncJobRecord(String jobId, String title, String operation, int totalTasks, long startedAt, long deadlineAt) {
            this.jobId = jobId;
            this.title = title;
            this.operation = operation;
            this.startTime = startedAt;
            this.deadlineAt = deadlineAt;
            this.status = JobStatus.RUNNING;
            this.totalTasks = totalTasks;
            this.completedTasks = 0;
            this.progressPercent = 0.0;
        }
    }

    private final ConcurrentMap<String, AsyncJobRecord> asyncJobs = new ConcurrentHashMap<>();
    public String simulateFailoverWorker() {
        List<WorkerInfo> online = registry.getAvailableWorkers().stream()
                .filter(w -> !w.paused && w.getStatus() == WorkerStatus.ONLINE)
                .toList();
        if (online.isEmpty()) return null;
        WorkerInfo target = online.get(new Random().nextInt(online.size()));
        toggleWorkerState(target.getWorkerId());
        return target.getWorkerId();
    }

    public AsyncJobRecord submitAsyncMatrixJob(String op, int rowsA, int colsA, int rowsB, int colsB,
                                              Map<String, Object> genA, Map<String, Object> genB,
                                              Double scalar, int taskCount) {
        return submitAsyncMatrixJob(op, rowsA, colsA, rowsB, colsB, genA, genB, scalar, taskCount, null, null);
    }

    public AsyncJobRecord submitAsyncMatrixJob(String op, int rowsA, int colsA, int rowsB, int colsB,
                                              Map<String, Object> genA, Map<String, Object> genB,
                                              Double scalar, int taskCount, double[][] inputA, double[][] inputB) {
        return submitAsyncMatrixJob(op, rowsA, colsA, rowsB, colsB, genA, genB, scalar, taskCount, inputA, inputB, null);
    }

    public synchronized AsyncJobRecord submitAsyncMatrixJob(String op, int rowsA, int colsA, int rowsB, int colsB,
                                              Map<String, Object> genA, Map<String, Object> genB,
                                              Double scalar, int taskCount, double[][] inputA, double[][] inputB,
                                              String idempotencyKey) {
        return submitAsyncMatrixJob(op, rowsA, colsA, rowsB, colsB, genA, genB, scalar, taskCount, inputA, inputB, idempotencyKey, false);
    }

    public synchronized AsyncJobRecord submitAsyncMatrixJob(String op, int rowsA, int colsA, int rowsB, int colsB,
                                              Map<String, Object> genA, Map<String, Object> genB,
                                              Double scalar, int taskCount, double[][] inputA, double[][] inputB,
                                              String idempotencyKey, boolean compareSequential) {
        MatrixJobPlan plan = new MatrixJobPlan(op, rowsA, colsA, rowsB, colsB, genA, genB, scalar, inputA, inputB);
        String safeOp = switch (plan.operation) {
            case "+" -> "add";
            case "-" -> "sub";
            case "%" -> "mod";
            case "*" -> "mul";
            case "x", "X" -> "mul";
            default -> plan.operation.replaceAll("[^A-Za-z0-9._-]", "_");
        };
        String jobId = "async-" + safeOp + "-" + UUID.randomUUID();
        String title = String.format("Tinh toan ma tran phan tan [%s]: A [%d x %d]", plan.operation, rowsA, colsA);
        // Partition work dynamically for active workers/machines and matrix scale without implicitly spawning workers.
        List<Task> tasks = plan.tasks(jobId, taskCount <= 0 ? partitionsForPlan(plan) : Math.min(1024, taskCount));
        Job job = new Job(jobId, title, tasks.get(0).getTaskType());
        job.setTasks(tasks);
        long startedAt = System.currentTimeMillis();
        long deadlineAt = startedAt + jobTimeoutMs;
        AsyncJobRecord record = new AsyncJobRecord(jobId, title, plan.operation, tasks.size(), startedAt, deadlineAt);
        record.totalUnits = tasks.stream().mapToLong(t -> Math.max(1, t.workUnits)).sum();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("operation", plan.operation); request.put("rowsA", rowsA); request.put("colsA", colsA);
        request.put("rowsB", rowsB); request.put("colsB", colsB); request.put("genA", genA); request.put("genB", genB);
        request.put("scalar", plan.scalar); request.put("taskCount", tasks.size()); request.put("requestedTaskCount", taskCount);
        request.put("compareSequential", compareSequential);
        request.put("matrixA", inputA); request.put("matrixB", inputB);
        String fingerprint = requestFingerprint(request);
        try {
            MasterStateStore.StoredJob previous = stateStore.findIdempotent(idempotencyKey, fingerprint);
            if (previous != null) {
                AsyncJobRecord existing = asyncJobs.get(previous.jobId);
                if (existing != null) return existing;
                throw new IllegalStateException("Idempotent Job exists but is not loaded in this Master session");
            }
        } catch (IOException e) { throw new IllegalArgumentException(e.getMessage(), e); }
        record.request = request;
        try { stateStore.createJob(jobId, request, record.startTime, record.deadlineAt, tasks.size(), idempotencyKey, fingerprint); }
        catch (IOException e) { throw new IllegalStateException("Master cannot durably accept this job: " + e.getMessage(), e); }
        asyncJobs.put(jobId, record);
        asyncJobExecutor.submit(() -> executeAsyncMatrixJob(record, plan, job));
        return record;
    }

    private String requestFingerprint(Object value) {
        try {
            Object canonical = value;
            if (value instanceof Map<?, ?> map) {
                TreeMap<String, Object> sorted = new TreeMap<>();
                map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalValue(item)));
                sorted.remove("taskCount");
                canonical = sorted;
            }
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(com.pbl4.core.network.JsonUtils.toJson(canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) { throw new IllegalStateException("Cannot fingerprint Job input", e); }
    }

    private Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalValue(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonicalValue).toList();
        return value;
    }

    private void resumeAsyncMatrixJob(AsyncJobRecord record) {
        long remainingMs = record.deadlineAt - System.currentTimeMillis();
        if (remainingMs <= 0) {
            record.error = "Bài toán đã hết thời hạn khi Master khởi động lại."; record.status = JobStatus.FAILED;
            persistJobState(record); return;
        }
        try {
            MatrixJobPlan plan = planFromRequest(record.request);
            List<Task> tasks = plan.tasks(record.jobId, Math.max(1, record.totalTasks));
            Job job = new Job(record.jobId, record.title, tasks.get(0).getTaskType()); job.setTasks(tasks);
            record.persistedResults.values().forEach(job::addResult);
            synchronized (record) { if (record.status == JobStatus.RECOVERING) record.status = JobStatus.RUNNING; }
            executeAsyncMatrixJob(record, plan, job, remainingMs);
        } catch (Exception e) {
            record.error = "Không thể phục hồi bài toán: " + e.getMessage(); record.status = JobStatus.FAILED; persistJobState(record);
        }
    }

    private void executeAsyncMatrixJob(AsyncJobRecord rec, MatrixJobPlan plan, Job job) {
        executeAsyncMatrixJob(rec, plan, job, jobTimeoutMs);
    }
    private void executeAsyncMatrixJob(AsyncJobRecord rec, MatrixJobPlan plan, Job job, long timeoutMs) {
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        monitor.scheduleAtFixedRate(() -> {
            Map<String, Object> snap = dispatcher.getJobSnapshot(rec.jobId);
            if (snap != null) {
                synchronized (rec) {
                    if (rec.status != JobStatus.RUNNING) return;
                    rec.completedTasks = (int) snap.getOrDefault("completedTasks", 0);
                    rec.progressPercent = (double) snap.getOrDefault("progressPercent", 0.0);
                    rec.completedUnits = ((Number) snap.get("completedUnits")).longValue();
                    @SuppressWarnings("unchecked")
                    Map<String, Long> completed = (Map<String, Long>) snap.get("workerCompletedTasks");
                    rec.workerCompletedTasks = Map.copyOf(completed);
                    @SuppressWarnings("unchecked")
                    Map<String, String> assignments = (Map<String, String>) snap.get("activeAssignments");
                    if (assignments != null) rec.activeWorkerAssignments = new ConcurrentHashMap<>(assignments);
                }
            }
        }, 80, 150, TimeUnit.MILLISECONDS);
        try {
            if (rec.status == JobStatus.FAILED || rec.status == JobStatus.CANCELLED) return;
            if (!dispatcher.execute(job, timeoutMs)) {
                if (rec.status == JobStatus.CANCELLED) return;
                throw new IllegalStateException(job.getFailureReason() != null ? job.getFailureReason()
                    : "Distributed computation failed or timed out. Check Worker logs and connections.");
            }
            Object output = plan.aggregate(job);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("success", true);
            summary.put("operation", plan.operation);
            summary.put("isScalar", output instanceof Number);
            summary.put("totalChunks", rec.totalTasks);
            summary.put("chunkSize", (int) Math.ceil((double) plan.a.getRows() / rec.totalTasks) + " dong / chunk (toi da)");
            summary.put("workersOnline", registry.getAvailableWorkers().size());
            List<String> finishedWorkerIds = job.getResults().values().stream().map(TaskResult::getWorkerId).distinct().sorted().toList();
            summary.put("workerIds", finishedWorkerIds);
            Map<String, String> workerDisplayNames = new LinkedHashMap<>();
            for (String wid : finishedWorkerIds) {
                workerDisplayNames.put(wid, resolveWorkerDisplayName(wid));
            }
            summary.put("workerDisplayNames", workerDisplayNames);
            Map<String, Long> finishedWorkers = new TreeMap<>();
            job.getResults().values().forEach(r -> finishedWorkers.merge(r.getWorkerId(), 1L, Long::sum));
            rec.workerCompletedTasks = Map.copyOf(finishedWorkers);
            summary.put("workerCompletedTasks", finishedWorkers);
            summary.put("machineIds", job.getResults().values().stream().map(r -> registry.getWorker(r.getWorkerId()))
                .filter(Objects::nonNull).map(w -> w.machineId).distinct().sorted().toList());
            if (output instanceof Matrix matrix) {
                summary.put("rows", matrix.getRows());
                summary.put("cols", matrix.getCols());
                boolean fullResult = (long) matrix.getRows() * matrix.getCols() <= 10_000;
                List<Integer> rows = fullResult ? java.util.stream.IntStream.range(0, matrix.getRows()).boxed().toList() : pickSampleIndices(matrix.getRows());
                List<Integer> cols = fullResult ? java.util.stream.IntStream.range(0, matrix.getCols()).boxed().toList() : pickSampleIndices(matrix.getCols());
                List<List<Double>> grid = new ArrayList<>();
                for (int r : rows) {
                    List<Double> row = new ArrayList<>();
                    for (int c : cols) row.add(matrix.get(r, c));
                    grid.add(row);
                }
                summary.put("isSampled", !fullResult);
                if (fullResult) summary.put("result", grid);
                summary.put("sampleGrid", grid);
                summary.put("rowIndices", rows);
                summary.put("colIndices", cols);
                rec.result = grid; rec.resultMatrix = matrix;
            } else {
                summary.put("isSampled", false);
                summary.put("resultScalar", output);
                if (output instanceof Double d) {
                    if (Double.isInfinite(d)) {
                        summary.put("overflow", true);
                        summary.put("note", "Định thức vượt quá giới hạn biểu diễn của số thực 64-bit IEEE 754 (Double.MAX_VALUE ~ 1.8e308).");
                    } else if (Double.isNaN(d)) {
                        summary.put("overflow", true);
                        summary.put("note", "Định thức không xác định (NaN) do bù trừ giữa các giá trị vô cực (+Infinity và -Infinity).");
                    }
                }
                rec.result = output;
            }
            long distElapsedMs = Math.max(1L, System.currentTimeMillis() - rec.startTime);
            summary.put("distributedTimeMs", System.currentTimeMillis() - rec.startTime);
            if (Boolean.TRUE.equals(rec.request.get("compareSequential"))) {
                Double seqTimeMs = measureSequentialBaseline(plan, summary);
                if (seqTimeMs != null) {
                    summary.put("sequentialTimeMs", Math.round(seqTimeMs * 100.0) / 100.0);
                    double speedup = seqTimeMs / (double) distElapsedMs;
                    summary.put("speedup", Math.round(speedup * 1000.0) / 1000.0);
                }
            }
            synchronized (rec) {
                if (rec.status != JobStatus.FAILED) {
                    rec.resultSummary = summary;
                    rec.completedTasks = rec.totalTasks;
                    rec.completedUnits = rec.totalUnits;
                    rec.progressPercent = 100.0;
                    // Publish completion after all result fields are available to the polling client.
                    rec.endTime = System.currentTimeMillis();
                    rec.durationMs = rec.endTime - rec.startTime;
                    stateStore.complete(rec.jobId, summary);
                    rec.status = JobStatus.COMPLETED;
                }
            }
        } catch (Exception e) {
            synchronized (rec) {
                if (rec.status == JobStatus.RUNNING) {
                    rec.error = e.getMessage();
                    rec.status = JobStatus.FAILED;
                    persistJobState(rec);
                }
            }
        } finally {
            monitor.shutdownNow();
            rec.activeWorkerAssignments = new ConcurrentHashMap<>();
            rec.endTime = System.currentTimeMillis();
            rec.durationMs = rec.endTime - rec.startTime;
            job.getResults().clear();
        }
    }

    private Double measureSequentialBaseline(MatrixJobPlan plan, Map<String, Object> summary) {
        try {
            long cells = plan.maxCells();
            String op = plan.operation;
            if (cells <= 1_000_000L) {
                long seqStart = System.nanoTime();
                switch (op) {
                    case "+" -> com.pbl4.computing.matrix.MatrixOperations.add(plan.a, plan.b);
                    case "-" -> com.pbl4.computing.matrix.MatrixOperations.subtract(plan.a, plan.b);
                    case "%" -> com.pbl4.computing.matrix.MatrixOperations.modulo(plan.a, plan.b);
                    case "x" -> com.pbl4.computing.matrix.MatrixOperations.multiply(plan.a, plan.b);
                    case "scalar" -> com.pbl4.computing.matrix.MatrixOperations.multiplyScalar(plan.a, plan.scalar);
                    case "t" -> com.pbl4.computing.matrix.MatrixOperations.transpose(plan.a);
                    case "inv" -> com.pbl4.computing.matrix.MatrixOperations.inverse(plan.a);
                    case "det" -> com.pbl4.computing.matrix.MatrixOperations.determinant(plan.a);
                    case "rank" -> com.pbl4.computing.matrix.MatrixOperations.rank(plan.a);
                    default -> { return null; }
                }
                summary.put("sequentialEstimated", false);
                return (System.nanoTime() - seqStart) / 1_000_000.0;
            }
            // For matrices > 1M cells up to 50M cells, calibrate sequentially on a representative row slice
            // without allocating an extra 400 MB matrix or stalling for minutes on O(N^3) operations.
            if (Set.of("+", "-", "%", "scalar", "t").contains(op)) {
                int totalRows = plan.a.getRows();
                int cols = plan.a.getCols();
                int sampleRows = Math.max(1, Math.min(totalRows, (int) Math.max(1L, 500_000L / Math.max(1, cols))));
                double[][] aData = plan.a.getData();
                double[][] bData = plan.b != null ? plan.b.getData() : null;
                double[] rowBuf = new double[cols];
                long seqStart = System.nanoTime();
                for (int r = 0; r < sampleRows; r++) {
                    double[] ra = aData[r];
                    if ("+".equals(op)) {
                        double[] rb = bData[r];
                        for (int c = 0; c < cols; c++) rowBuf[c] = ra[c] + rb[c];
                    } else if ("-".equals(op)) {
                        double[] rb = bData[r];
                        for (int c = 0; c < cols; c++) rowBuf[c] = ra[c] - rb[c];
                    } else if ("%".equals(op)) {
                        double[] rb = bData[r];
                        for (int c = 0; c < cols; c++) rowBuf[c] = rb[c] == 0 ? 0.0 : ra[c] % rb[c];
                    } else if ("scalar".equals(op)) {
                        for (int c = 0; c < cols; c++) rowBuf[c] = ra[c] * plan.scalar;
                    } else {
                        for (int c = 0; c < cols; c++) rowBuf[c] = ra[c];
                    }
                }
                double sampleMs = (System.nanoTime() - seqStart) / 1_000_000.0;
                summary.put("sequentialEstimated", sampleRows < totalRows);
                return sampleMs * ((double) totalRows / sampleRows);
            }
            if ("x".equals(op)) {
                int totalRows = plan.a.getRows();
                int inner = plan.a.getCols();
                int colsB = plan.b.getCols();
                int sampleRows = Math.max(1, Math.min(totalRows, Math.max(2, (int) (100_000L / Math.max(1, inner)))));
                double[][] aData = plan.a.getData();
                double[][] bData = plan.b.getData();
                double[] rowBuf = new double[colsB];
                long seqStart = System.nanoTime();
                for (int r = 0; r < sampleRows; r++) {
                    Arrays.fill(rowBuf, 0.0);
                    double[] ra = aData[r];
                    for (int k = 0; k < inner; k++) {
                        double aik = ra[k];
                        if (aik == 0.0) continue;
                        double[] bk = bData[k];
                        for (int c = 0; c < colsB; c++) {
                            rowBuf[c] += aik * bk[c];
                        }
                    }
                }
                double sampleMs = (System.nanoTime() - seqStart) / 1_000_000.0;
                summary.put("sequentialEstimated", sampleRows < totalRows);
                return sampleMs * ((double) totalRows / sampleRows);
            }
            summary.put("sequentialNote", "Ma trận > 1.000.000 phần tử bỏ qua chạy tuần tự trực tiếp cho phép tính bậc 3.");
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void persistJobState(AsyncJobRecord record) {
        try { stateStore.updateState(record.jobId, record.status.name(), record.error); }
        catch (IOException e) { System.err.println("[Master] Could not persist Job state " + record.jobId + ": " + e.getMessage()); }
    }
    private List<Integer> pickSampleIndices(int total) {
        List<Integer> list = new ArrayList<>();
        if (total <= 6) {
            for (int i = 0; i < total; i++) list.add(i);
            return list;
        }
        list.add(0);
        list.add(1);
        list.add(2);
        int mid = total / 2;
        if (!list.contains(mid) && mid < total - 3) {
            list.add(mid);
        }
        for (int i = Math.max(3, total - 3); i < total; i++) {
            if (!list.contains(i)) list.add(i);
        }
        return list;
    }

    public Map<String, Object> getJobProgress(String jobId) {
        AsyncJobRecord rec = asyncJobs.get(jobId);
        if (rec == null) return null;

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jobId", rec.jobId);
        resp.put("title", rec.title);
        resp.put("operation", rec.operation);
        resp.put("status", rec.status.name());
        resp.put("totalTasks", rec.totalTasks);
        resp.put("completedTasks", rec.completedTasks);
        resp.put("progressPercent", rec.progressPercent);

        long now = System.currentTimeMillis();
        long elapsed = (rec.endTime > 0 ? rec.endTime : now) - rec.startTime;
        resp.put("elapsedMs", elapsed);

        resp.put("totalUnits", rec.totalUnits); resp.put("completedUnits", rec.completedUnits);
        if (rec.completedUnits > 0 && rec.status == JobStatus.RUNNING) {
            resp.put("etaMs", Math.round((double) elapsed * Math.max(0, rec.totalUnits - rec.completedUnits) / rec.completedUnits));
        } else {
            resp.put("etaMs", rec.status == JobStatus.COMPLETED ? 0 : null);
        }

        List<Map<String, Object>> rawEvents = new ArrayList<>(dispatcher.getRecentFailoverEvents());
        rawEvents.addAll(rec.failoverLogs);
        List<Map<String, Object>> events = new ArrayList<>(rawEvents.size());
        for (Map<String, Object> raw : rawEvents) {
            Map<String, Object> copy = new LinkedHashMap<>(raw);
            Object widObj = copy.get("workerId");
            if (widObj instanceof String wid) {
                copy.put("displayName", resolveWorkerDisplayName(wid));
            }
            events.add(copy);
        }
        resp.put("failoverEvents", events);

        List<WorkerInfo> allWorkers = registry.getAllWorkers();
        List<Map<String, Object>> fleet = new ArrayList<>();
        for (WorkerInfo w : allWorkers) {
            Map<String, Object> wMap = new LinkedHashMap<>();
            wMap.put("workerId", w.getWorkerId());
            wMap.put("displayName", w.displayName);
            wMap.put("machineId", w.machineId); wMap.put("machineName", w.machineName);
            wMap.put("hostParallelism", w.hostParallelism);
            wMap.put("processGroupId", w.processGroupId); wMap.put("groupParallelism", w.groupParallelism);
            wMap.put("jobCompletedTasks", rec.workerCompletedTasks.getOrDefault(w.getWorkerId(), 0L));
            wMap.put("status", w.getStatus().name());
            wMap.put("paused", w.paused); wMap.put("quarantined", w.quarantined);
            wMap.put("activeTasks", w.getActiveTasks());
            wMap.put("completedTasks", w.getCompletedTasks());
            wMap.put("assignedTask", rec.activeWorkerAssignments.get(w.getWorkerId()));
            scheduler.eligible(w);
            wMap.put("admissionReason", w.admissionReason);
            wMap.put("host", w.getHost());
            wMap.put("port", w.getPort());
            wMap.put("metrics", w.metrics);
            fleet.add(wMap);
        }
        resp.put("workerFleet", fleet);
        Map<String, Object> snapshot = dispatcher.getJobSnapshot(jobId);
        if (snapshot != null) {
            resp.put("queueSize", snapshot.get("queueSize"));
            resp.put("inflightCount", snapshot.get("inflightCount"));
            if (((Number) snapshot.get("inflightCount")).intValue() == 0)
                resp.put("waitingReason", scheduler.describeAvailability());
        }

        if (rec.status == JobStatus.COMPLETED) {
            resp.put("result", rec.result);
            if (rec.resultSummary != null) {
                Map<String, Object> summaryCopy = new LinkedHashMap<>(rec.resultSummary);
                Object widsObj = summaryCopy.get("workerIds");
                if (widsObj instanceof List<?> wids) {
                    Map<String, String> liveNames = new LinkedHashMap<>();
                    for (Object o : wids) {
                        if (o instanceof String wid) liveNames.put(wid, resolveWorkerDisplayName(wid));
                    }
                    summaryCopy.put("workerDisplayNames", liveNames);
                }
                resp.put("resultSummary", summaryCopy);
            } else {
                resp.put("resultSummary", null);
            }
            resp.put("durationMs", rec.durationMs);
        } else if (rec.status == JobStatus.FAILED || rec.status == JobStatus.CANCELLED) {
            resp.put("error", rec.error);
        }

        return resp;
    }

    public List<Map<String, Object>> getAsyncJobList() {
        return asyncJobs.values().stream().sorted(Comparator.comparingLong(rec -> rec.startTime)).map(rec -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jobId", rec.jobId); row.put("title", rec.title); row.put("operation", rec.operation);
            row.put("status", rec.status.name()); row.put("totalTasks", rec.totalTasks); row.put("completedTasks", rec.completedTasks);
            row.put("progressPercent", rec.progressPercent); row.put("startedAt", rec.startTime); row.put("error", rec.error);
            return row;
        }).toList();
    }

    public Map<String, Object> getAsyncResultPage(String jobId, int startRow, int requestedRows) {
        return getAsyncResultPage(jobId, startRow, requestedRows, 0, 256);
    }

    public Map<String, Object> getAsyncResultPage(String jobId, int startRow, int requestedRows,
                                                  int startCol, int requestedCols) {
        AsyncJobRecord rec = asyncJobs.get(jobId);
        if (rec == null) return null;
        if (rec.status != JobStatus.COMPLETED) return Map.of("jobId", jobId, "status", rec.status.name());
        if (rec.resultMatrix == null) return Map.of("jobId", jobId, "status", "COMPLETED", "scalar", rec.result);
        Matrix matrix = rec.resultMatrix;
        if (startRow < 0 || startRow >= matrix.getRows() || requestedRows < 1
            || startCol < 0 || startCol >= matrix.getCols() || requestedCols < 1)
            throw new IllegalArgumentException("Result page coordinates are outside the matrix");
        int cols = Math.min(Math.min(20_000, requestedCols), matrix.getCols() - startCol);
        int rowLimit = Math.min(Math.min(128, requestedRows), Math.max(1, 20_000 / cols));
        int end = (int) Math.min(matrix.getRows(), (long) startRow + rowLimit);
        List<List<Double>> dataRows = new ArrayList<>(end - startRow);
        for (int r = startRow; r < end; r++) {
            List<Double> row = new ArrayList<>(cols);
            for (int c = startCol; c < startCol + cols; c++) row.add(matrix.get(r, c));
            dataRows.add(row);
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("jobId", jobId); page.put("status", "COMPLETED"); page.put("rows", matrix.getRows()); page.put("cols", matrix.getCols());
        page.put("startRow", startRow); page.put("rowCount", end - startRow); page.put("startCol", startCol); page.put("colCount", cols); page.put("result", dataRows);
        return page;
    }

    public boolean cancelAsyncJob(String jobId) {
        AsyncJobRecord rec = asyncJobs.get(jobId);
        if (rec != null) {
            synchronized (rec) {
                if (rec.status != JobStatus.RUNNING) return false;
                rec.error = "Bài toán đã bị hủy bởi người dùng.";
                rec.status = JobStatus.CANCELLED;
                dispatcher.cancel(jobId);
                persistJobState(rec);
                return true;
            }
        }
        return false;
    }
}
