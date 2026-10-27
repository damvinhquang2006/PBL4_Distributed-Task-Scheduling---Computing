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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class MasterServer {
    private final int port;
    private final String bindHost;
    private final String jarSha256 = com.pbl4.core.network.RuntimeBuild.SHA256;
    private final String session = UUID.randomUUID().toString();
    private final WorkerRegistry registry = new WorkerRegistry();
    private final TaskScheduler scheduler = new TaskScheduler(registry);
    private final JobDispatcher dispatcher = new JobDispatcher(session, scheduler);
    private final AtomicBoolean running = new AtomicBoolean();
    private ServerSocket serverSocket;
    private ExecutorService connectionThreadPool;
    private long jobTimeoutMs = 300_000;
    public MasterServer(int port) { this(port, "0.0.0.0"); }
    public MasterServer(int port, String bindHost) {
        this.port = port; this.bindHost = bindHost;
        registry.setOnWorkerFailedListener(dispatcher::workerFailed);
    }
    public void setJobTimeoutMs(long value) {
        if (value <= 0) throw new IllegalArgumentException("job timeout must be positive"); jobTimeoutMs = value;
    }
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
            if (request.protocolVersion != Message.CURRENT_VERSION) response = reply(MessageType.REGISTER_REJECTED, "Protocol v3 required; update the JAR on both laptops and restart Master/Workers");
            else switch (request.getType()) {
                case REGISTER_WORKER -> response = register(request);
                case HEARTBEAT -> {
                    boolean known = session.equals(request.masterSession) && registry.heartbeat(request.getSenderId(), request.workerSession, request.metrics);
                    WorkerInfo w = registry.getWorker(request.getSenderId());
                    response = reply(known ? MessageType.HEARTBEAT_ACK : MessageType.REGISTER_REQUIRED,
                        known && w.paused ? "PAUSED" : "READY");
                }
                case TASK_RESULT -> {
                    dispatcher.result(request.getTaskResult());
                    WorkerInfo w = registry.getWorker(request.getSenderId());
                    if (w != null && Objects.equals(w.sessionId, request.workerSession) && request.metrics != null) w.metrics = request.metrics;
                    response = reply(MessageType.RESULT_ACK, "Received (including stale or duplicate result)");
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
        return (int) Math.min(256, Math.max(1, 2L * scheduler.effectiveCapacity()));
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
    public synchronized void stop() {
        running.set(false);

        dispatcher.close(); registry.shutdown();
        asyncJobExecutor.shutdownNow();


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
        public volatile long endTime;
        public volatile JobStatus status;
        public final int totalTasks;
        public volatile int completedTasks;
        public volatile double progressPercent;
        public long totalUnits;
        public volatile long completedUnits;
        public volatile Map<String, Long> workerCompletedTasks = Map.of();
        public volatile long durationMs;
        public volatile Object result;
        public volatile Map<String, Object> resultSummary;
        public volatile String error;
        public final List<Map<String, Object>> failoverLogs = Collections.synchronizedList(new ArrayList<>());
        public volatile Map<String, String> activeWorkerAssignments = new ConcurrentHashMap<>();

        public AsyncJobRecord(String jobId, String title, String operation, int totalTasks) {
            this.jobId = jobId;
            this.title = title;
            this.operation = operation;
            this.startTime = System.currentTimeMillis();
            this.status = JobStatus.RUNNING;
            this.totalTasks = totalTasks;
            this.completedTasks = 0;
            this.progressPercent = 0.0;
        }
    }

    private final ConcurrentMap<String, AsyncJobRecord> asyncJobs = new ConcurrentHashMap<>();
    private final ExecutorService asyncJobExecutor = Executors.newCachedThreadPool();


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
        MatrixJobPlan plan = new MatrixJobPlan(op, rowsA, colsA, rowsB, colsB, genA, genB, scalar, inputA, inputB);
        String jobId = "async-" + plan.operation + "-" + UUID.randomUUID();
        String title = String.format("Tinh toan ma tran phan tan [%s]: A [%d x %d]", plan.operation, rowsA, colsA);
        // Partition work independently of the worker count; never start local workers implicitly.
        List<Task> tasks = plan.tasks(jobId, taskCount <= 0 ? partitions() : Math.min(256, taskCount));
        Job job = new Job(jobId, title, tasks.get(0).getTaskType());
        job.setTasks(tasks);
        AsyncJobRecord record = new AsyncJobRecord(jobId, title, plan.operation, tasks.size());
        record.totalUnits = tasks.stream().mapToLong(t -> Math.max(1, t.workUnits)).sum();
        asyncJobs.put(jobId, record);
        asyncJobExecutor.submit(() -> executeAsyncMatrixJob(record, plan, job));
        return record;
    }

    private void executeAsyncMatrixJob(AsyncJobRecord rec, MatrixJobPlan plan, Job job) {
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
            if (rec.status == JobStatus.FAILED) return;
            if (!dispatcher.execute(job, jobTimeoutMs)) {
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
            summary.put("workerIds", job.getResults().values().stream().map(TaskResult::getWorkerId).distinct().sorted().toList());
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
                rec.result = grid;
            } else {
                summary.put("isSampled", false);
                summary.put("resultScalar", output);
                rec.result = output;
            }
            summary.put("distributedTimeMs", System.currentTimeMillis() - rec.startTime);
            synchronized (rec) {
                if (rec.status != JobStatus.FAILED) {
                    rec.resultSummary = summary;
                    rec.completedTasks = rec.totalTasks;
                    rec.completedUnits = rec.totalUnits;
                    rec.progressPercent = 100.0;
                    // Publish completion after all result fields are available to the polling client.
                    rec.endTime = System.currentTimeMillis();
                    rec.durationMs = rec.endTime - rec.startTime;
                    rec.status = JobStatus.COMPLETED;
                }
            }
        } catch (Exception e) {
            synchronized (rec) {
                if (rec.status != JobStatus.FAILED) {
                    rec.error = e.getMessage();
                    rec.status = JobStatus.FAILED;
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

        List<Map<String, Object>> events = new ArrayList<>(dispatcher.getRecentFailoverEvents());
        events.addAll(rec.failoverLogs);
        resp.put("failoverEvents", events);

        List<WorkerInfo> allWorkers = registry.getAllWorkers();
        List<Map<String, Object>> fleet = new ArrayList<>();
        for (WorkerInfo w : allWorkers) {
            Map<String, Object> wMap = new LinkedHashMap<>();
            wMap.put("workerId", w.getWorkerId());
            wMap.put("machineId", w.machineId); wMap.put("machineName", w.machineName);
            wMap.put("hostParallelism", w.hostParallelism);
            wMap.put("jobCompletedTasks", rec.workerCompletedTasks.getOrDefault(w.getWorkerId(), 0L));
            wMap.put("status", w.getStatus().name());
            wMap.put("paused", w.paused);
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
            resp.put("resultSummary", rec.resultSummary);
            resp.put("durationMs", rec.durationMs);
        } else if (rec.status == JobStatus.FAILED) {
            resp.put("error", rec.error);
        }

        return resp;
    }

    public boolean cancelAsyncJob(String jobId) {
        AsyncJobRecord rec = asyncJobs.get(jobId);
        if (rec != null) {
            synchronized (rec) {
                if (rec.status != JobStatus.RUNNING) return false;
                rec.error = "Bài toán đã bị hủy bởi người dùng.";
                rec.status = JobStatus.FAILED;
                dispatcher.cancel(jobId);
                return true;
            }
        }
        return false;
    }
}
