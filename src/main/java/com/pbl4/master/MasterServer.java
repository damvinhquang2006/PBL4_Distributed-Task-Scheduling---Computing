package com.pbl4.master;

import com.pbl4.computing.graph.Graph;
import com.pbl4.computing.matrix.Matrix;
import com.pbl4.core.model.*;
import com.pbl4.core.network.SocketMessenger;
import com.pbl4.master.aggregate.ResultAggregator;
import com.pbl4.master.scheduler.TaskScheduler;
import com.pbl4.master.split.TaskSplitter;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Node dieu phoi trung tam (Master Node) chiu trach nhiem nhan job, chia nho task,
 * lap lich can bang tai va tong hop ket qua.
 */
public class MasterServer {
    private final int port;
    private final WorkerRegistry registry;
    private final TaskScheduler scheduler;
    private final Map<String, Job> activeJobs = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> jobLatches = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private ExecutorService connectionThreadPool;

    public MasterServer(int port) {
        this.port = port;
        this.registry = new WorkerRegistry();
        this.scheduler = new TaskScheduler(registry);

        // Thiet lap co che tu dong tai phan phoi (Failover) khi Worker chet
        this.registry.setOnWorkerFailedListener(this::handleWorkerFailure);
    }

    public synchronized void start() throws IOException {
        if (running.get()) return;
        running.set(true);

        connectionThreadPool = Executors.newCachedThreadPool();
        serverSocket = new ServerSocket(port);
        System.out.printf("[Master Server] Khoi dong thanh cong, dang lang nghe tai port %d...%n", port);

        Thread acceptThread = new Thread(this::listenForConnections, "Master-AcceptThread");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void listenForConnections() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                connectionThreadPool.submit(() -> handleClientSocket(socket));
            } catch (IOException e) {
                if (!running.get()) break;
                System.err.printf("[Master] Loi nhan ket noi: %s%n", e.getMessage());
            }
        }
    }

    private void handleClientSocket(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            Message msg = SocketMessenger.receiveMessage(reader);
            if (msg == null) return;

            switch (msg.getType()) {
                case REGISTER_WORKER:
                    if (msg.getWorkerInfo() != null) {
                        registry.register(msg.getWorkerInfo());
                        SocketMessenger.sendMessage(writer, Message.registerAck(msg.getSenderId()));
                    }
                    break;

                case HEARTBEAT:
                    registry.recordHeartbeat(msg.getSenderId());
                    SocketMessenger.sendMessage(writer, Message.heartbeatAck(msg.getSenderId()));
                    break;

                case TASK_RESULT:
                    handleTaskResult(msg.getTaskResult());
                    break;

                default:
                    break;
            }
        } catch (Exception e) {
            // Log loi ket noi thong thuong
        }
    }

    private void handleTaskResult(TaskResult result) {
        if (result == null) return;

        WorkerInfo worker = registry.getWorker(result.getWorkerId());
        if (worker != null) {
            worker.decrementActiveTasks();
            if (result.isSuccess()) {
                worker.incrementCompletedTasks();
            }
        }

        Job job = activeJobs.get(result.getJobId());
        if (job != null) {
            job.addResult(result);
            System.out.printf("[Master] Da nhan ket qua Task [%s] tu %s (Tien do: %d/%d tasks)%n",
                    result.getTaskId(), result.getWorkerId(), job.getResults().size(), job.getTasks().size());

            if (job.isComplete()) {
                CountDownLatch latch = jobLatches.get(job.getJobId());
                if (latch != null) {
                    latch.countDown();
                }
            }
        }
    }

    /**
     * Tu dong dieu phoi lai cac task do dang khi mot Worker bi ngat ket noi (Fault Tolerance)
     */
    private void handleWorkerFailure(WorkerInfo deadWorker) {
        String deadId = deadWorker.getWorkerId();
        for (Job job : activeJobs.values()) {
            if (!job.isComplete()) {
                for (Task task : job.getTasks()) {
                    if (deadId.equals(task.getWorkerAssigned()) && !job.getResults().containsKey(task.getTaskId())) {
                        System.out.printf("[Master - Failover] Tai dieu phoi Task [%s] cua Job [%s] do Worker [%s] bi ngat ket noi!%n",
                                task.getTaskId(), job.getJobId(), deadId);
                        task.incrementRetryCount();
                        WorkerInfo newWorker = scheduler.selectWorker();
                        if (newWorker != null) {
                            scheduler.dispatchTask(task, newWorker);
                        } else {
                            System.err.println("[Master - Failover] Khong con Worker nao kha dung de nhan lai task!");
                        }
                    }
                }
            }
        }
    }

    /**
     * Thuc thi mot Job hoan chinh: Phan phoi cac task, cho ket qua va xu ly ngoai le/timeout.
     */
    public boolean executeJob(Job job, long timeoutMs) {
        activeJobs.put(job.getJobId(), job);
        CountDownLatch latch = new CountDownLatch(1);
        jobLatches.put(job.getJobId(), latch);

        job.setStatus(JobStatus.RUNNING);
        job.setStartTime(System.currentTimeMillis());

        // Phan phoi tung task toi cac Worker dua tren thuat toan can bang tai
        for (Task task : job.getTasks()) {
            WorkerInfo worker = scheduler.selectWorker();
            if (worker == null) {
                System.err.println("[Master] Khong co Worker nao dang truc tuyen de xu ly cong viec!");
                job.setStatus(JobStatus.FAILED);
                return false;
            }
            boolean sent = scheduler.dispatchTask(task, worker);
            if (!sent) {
                System.err.printf("[Master] Gui Task [%s] that bai!%n", task.getTaskId());
            }
        }

        boolean completed = false;
        try {
            completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (completed) {
                job.setStatus(JobStatus.COMPLETED);
                job.setEndTime(System.currentTimeMillis());
            } else {
                job.setStatus(JobStatus.FAILED);
                System.err.printf("[Master] Job [%s] qua thoi gian cho (Timeout %d ms)!%n", job.getJobId(), timeoutMs);
            }
        } catch (InterruptedException e) {
            job.setStatus(JobStatus.FAILED);
            Thread.currentThread().interrupt();
        } finally {
            jobLatches.remove(job.getJobId());
        }

        return completed;
    }

    // ==========================================
    // CAC HAM XU LY NGHIEP VU CAP CAO (API)
    // ==========================================

    /**
     * Vi du khoi dau: Tinh toan song song bieu thuc (VD: 1+2 va 3*2 -> Tong hop = 9)
     */
    public Double runArithmeticStarter(String expr1, String expr2, String topOp) {
        String jobId = "arithmetic-" + System.currentTimeMillis();
        Job job = new Job(jobId, "Bieu thuc song song: (" + expr1 + ") " + topOp + " (" + expr2 + ")", TaskType.ARITHMETIC);
        
        List<Task> tasks = TaskSplitter.splitArithmetic(jobId, expr1, expr2);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB KHOI DAU: (%s) %s (%s) ===%n", expr1, topOp, expr2);
        boolean ok = executeJob(job, 10000);
        if (ok) {
            double res = ResultAggregator.aggregateArithmetic(job, topOp);
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
        String jobId = "mat-" + (isAdd ? "add" : "sub") + "-" + System.currentTimeMillis();

        int workerCount = Math.max(1, registry.getAvailableWorkers().size());
        Job job = new Job(jobId, String.format("%s ma tran [%d x %d]", opName, A.getRows(), A.getCols()), type);
        List<Task> tasks = TaskSplitter.splitMatrixAddSub(jobId, A, B, type, workerCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB %s MA TRAN [%d x %d] TREN %d WORKERS ===%n",
                opName.toUpperCase(), A.getRows(), A.getCols(), tasks.size());

        boolean ok = executeJob(job, 30000);
        if (ok) {
            Matrix res = ResultAggregator.aggregateMatrix(job, A.getRows(), A.getCols());
            System.out.printf(">>> HOAN THANH %s MA TRAN TRONG %d ms!%n", opName, job.getDurationMs());
            return res;
        }
        return null;
    }

    /**
     * Phep chia lay du (%) 2 ma tran phan tan C = A % B
     */
    public Matrix runMatrixModulo(Matrix A, Matrix B) {
        String jobId = "mat-mod-" + System.currentTimeMillis();
        int workerCount = Math.max(1, registry.getAvailableWorkers().size());

        Job job = new Job(jobId, String.format("Chia lay du (%%) ma tran [%d x %d]", A.getRows(), A.getCols()), TaskType.MATRIX_MOD);
        List<Task> tasks = TaskSplitter.splitMatrixAddSub(jobId, A, B, TaskType.MATRIX_MOD, workerCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB CHIA LAY DU (%%) MA TRAN [%d x %d] TREN %d WORKERS ===%n",
                A.getRows(), A.getCols(), tasks.size());

        boolean ok = executeJob(job, 30000);
        if (ok) {
            Matrix res = ResultAggregator.aggregateMatrix(job, A.getRows(), A.getCols());
            System.out.printf(">>> HOAN THANH CHIA LAY DU (%%) MA TRAN TRONG %d ms!%n", job.getDurationMs());
            return res;
        }
        return null;
    }

    /**
     * Phep nhan 2 ma tran phan tan C = A * B
     */
    public Matrix runMatrixMultiplication(Matrix A, Matrix B) {
        String jobId = "mat-mul-" + System.currentTimeMillis();
        int workerCount = Math.max(1, registry.getAvailableWorkers().size());

        Job job = new Job(jobId, String.format("Nhan ma tran [%d x %d] * [%d x %d]",
                A.getRows(), A.getCols(), B.getRows(), B.getCols()), TaskType.MATRIX_MULTIPLY);
        List<Task> tasks = TaskSplitter.splitMatrixMultiply(jobId, A, B, workerCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB NHAN MA TRAN [%d x %d] * [%d x %d] TREN %d WORKERS ===%n",
                A.getRows(), A.getCols(), B.getRows(), B.getCols(), tasks.size());

        boolean ok = executeJob(job, 60000);
        if (ok) {
            Matrix res = ResultAggregator.aggregateMatrix(job, A.getRows(), B.getCols());
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

        String jobId = "mat-det-" + System.currentTimeMillis();
        int workerCount = Math.max(1, registry.getAvailableWorkers().size());

        Job job = new Job(jobId, String.format("Tinh dinh thuc ma tran [%d x %d]",
                A.getRows(), A.getCols()), TaskType.MATRIX_DETERMINANT);
        List<Task> tasks = TaskSplitter.splitMatrixDeterminant(jobId, A, workerCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB TINH DINH THUC MA TRAN [%d x %d] TREN %d WORKERS ===%n",
                A.getRows(), A.getCols(), tasks.size());

        boolean ok = executeJob(job, 60000);
        if (ok) {
            double determinant = ResultAggregator.aggregateMatrixDeterminant(job);
            System.out.printf(">>> HOAN THANH TINH DINH THUC TRONG %d ms!%n", job.getDurationMs());
            return determinant;
        }
        return null;
    }

    /**
     * Tinh hang ma tran phan tan bang cach rut gon tung lat cat dong tren Worker.
     */
    public Integer runMatrixRank(Matrix A) {
        String jobId = "mat-rank-" + System.currentTimeMillis();
        int workerCount = Math.max(1, registry.getAvailableWorkers().size());

        Job job = new Job(jobId, String.format("Tinh hang ma tran [%d x %d]",
                A.getRows(), A.getCols()), TaskType.MATRIX_RANK);
        List<Task> tasks = TaskSplitter.splitMatrixRank(jobId, A, workerCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU JOB TINH HANG MA TRAN [%d x %d] TREN %d WORKERS ===%n",
                A.getRows(), A.getCols(), tasks.size());

        boolean ok = executeJob(job, 60000);
        if (ok) {
            int rank = ResultAggregator.aggregateMatrixRank(job, A.getCols());
            System.out.printf(">>> HOAN THANH TINH HANG MA TRAN TRONG %d ms!%n", job.getDurationMs());
            return rank;
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
        String jobId = "graph-sp-" + System.currentTimeMillis();
        int workerCount = Math.max(1, registry.getAvailableWorkers().size());

        Job job = new Job(jobId, String.format("Tim duong di ngan nhat do thi (%d dinh)",
                graph.getVertices()), TaskType.GRAPH_SHORTEST_PATH);
        List<Task> tasks = TaskSplitter.splitGraphShortestPath(jobId, graph, workerCount);
        job.setTasks(tasks);

        System.out.printf("%n=== BAT DAU TIM DUONG DI NGAN NHAT PHAN TAN (%d DINH) TREN %d WORKERS ===%n",
                graph.getVertices(), tasks.size());

        boolean ok = executeJob(job, 30000);
        if (ok) {
            double[][] dist = ResultAggregator.aggregateGraphShortestPath(job, graph.getVertices());
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
        if (worker == null) {
            System.err.printf("[Master] Khong tim thay Worker [%s] trong danh sach!%n", workerId);
            return false;
        }

        boolean willBeOnline = (worker.getStatus() != WorkerStatus.ONLINE);
        WorkerStatus newStatus = willBeOnline ? WorkerStatus.ONLINE : WorkerStatus.OFFLINE;
        worker.setStatus(newStatus);
        if (willBeOnline) {
            worker.setLastHeartbeatTime(System.currentTimeMillis());
        }

        try {
            Message msg = Message.setWorkerState(workerId, willBeOnline);
            SocketMessenger.sendOneWay(worker.getHost(), worker.getPort(), msg, 3000);
            System.out.printf("[Master] Da gui lenh chuyen Worker [%s] sang trang thai: %s%n",
                    workerId, newStatus);
            return true;
        } catch (Exception e) {
            System.out.printf("[Master] Da cap nhat trang thai Worker [%s] sang %s tren Master (Khong ket noi duoc den Worker qua socket: %s)%n",
                    workerId, newStatus, e.getMessage());
            return true;
        }
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);

        registry.shutdown();
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

    public TaskScheduler getScheduler() {
        return scheduler;
    }

    public boolean isRunning() {
        return running.get();
    }
}
