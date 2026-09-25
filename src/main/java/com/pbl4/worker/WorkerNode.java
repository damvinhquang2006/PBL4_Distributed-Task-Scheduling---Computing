package com.pbl4.worker;

import com.pbl4.core.model.Message;
import com.pbl4.core.model.MessageType;
import com.pbl4.core.model.Task;
import com.pbl4.core.model.TaskResult;
import com.pbl4.core.model.WorkerInfo;
import com.pbl4.core.network.SocketMessenger;
import com.pbl4.worker.executor.TaskExecutor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker Node chay doc lap, lang nghe tac vu tu Master tren cong duoc chi dinh (VD: 51, 52, 53).
 */
public class WorkerNode {
    private final WorkerConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private ServerSocket serverSocket;
    private ExecutorService taskThreadPool;
    private ScheduledExecutorService heartbeatScheduler;

    public WorkerNode(WorkerConfig config) {
        this.config = config;
    }

    public synchronized void start() throws IOException {
        if (running.get()) return;
        running.set(true);

        // Khoi tao thread pool thuc thi tinh toan dua theo capacity (so loi CPU)
        taskThreadPool = Executors.newFixedThreadPool(config.getCapacity());

        // Lang nghe ket noi den tu Master tren cong vat ly cua Worker (vi du 51, 52, 53)
        serverSocket = new ServerSocket(config.getPort());
        System.out.printf("[%s] Lang nghe tren %s:%d (Capacity: %d threads)%n",
                config.getWorkerId(), config.getHost(), config.getPort(), config.getCapacity());

        // Dang ky voi Master
        registerWithMaster();

        // Bat dau chu ky gui Heartbeat
        startHeartbeat();

        // Bat dau luong lang nghe nhan tac vu
        Thread listenerThread = new Thread(this::listenForTasks, "WorkerListener-" + config.getWorkerId());
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void registerWithMaster() {
        try {
            WorkerInfo info = new WorkerInfo(
                    config.getWorkerId(),
                    config.getHost(),
                    config.getPort(),
                    config.getCapacity()
            );
            Message regMsg = Message.register(info);
            Message ack = SocketMessenger.sendAndReceive(config.getMasterHost(), config.getMasterPort(), regMsg, 4000);
            if (ack != null && ack.getType() == MessageType.REGISTER_ACK) {
                System.out.printf("[%s] Dang ky thanh cong voi Master tai %s:%d%n",
                        config.getWorkerId(), config.getMasterHost(), config.getMasterPort());
            } else {
                System.err.printf("[%s] Canh bao: Khong nhan duoc ACK dang ky tu Master!%n", config.getWorkerId());
            }
        } catch (Exception e) {
            System.err.printf("[%s] Chua the ket noi toi Master (%s:%d): %s. Se thu lai qua heartbeat.%n",
                    config.getWorkerId(), config.getMasterHost(), config.getMasterPort(), e.getMessage());
        }
    }

    private void startHeartbeat() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            // Neu Worker dang o trang thai OFF, khong gui heartbeat de Master nhan biet node bi tam dung/offline
            if (!enabled.get()) return;

            try {
                Message hb = Message.heartbeat(config.getWorkerId());
                SocketMessenger.sendOneWay(config.getMasterHost(), config.getMasterPort(), hb, 2000);
            } catch (Exception ignored) {
                // Heartbeat that bai neu Master tam ban hoac khoi dong lai
            }
        }, config.getHeartbeatIntervalSeconds(), config.getHeartbeatIntervalSeconds(), TimeUnit.SECONDS);
    }

    private void listenForTasks() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                taskThreadPool.submit(() -> handleIncomingConnection(clientSocket));
            } catch (IOException e) {
                if (!running.get()) break;
                System.err.printf("[%s] Loi nhan ket noi: %s%n", config.getWorkerId(), e.getMessage());
            }
        }
    }

    private void handleIncomingConnection(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            Message msg = SocketMessenger.receiveMessage(reader);
            if (msg == null) return;

            if (msg.getType() == MessageType.ASSIGN_TASK && msg.getTask() != null) {
                Task task = msg.getTask();

                if (!enabled.get()) {
                    System.err.printf("[%s] Canh bao: Worker dang o trang thai OFF, tu choi xu ly Task [%s]!%n",
                            config.getWorkerId(), task.getTaskId());
                    TaskResult failed = TaskResult.failure(task.getTaskId(), task.getJobId(), config.getWorkerId(),
                            "Worker dang o trang thai OFF (Tam dung)");
                    sendResultToMaster(failed);
                    return;
                }

                System.out.printf("%n[%s] =======================================================%n", config.getWorkerId());
                System.out.printf("[%s] >>> [NHAN TASK THANH CONG] Task ID: [%s]%n", config.getWorkerId(), task.getTaskId());
                System.out.printf("[%s]  |-- Loai tac vu: %s | Job ID: %s%n",
                        config.getWorkerId(), task.getTaskType(), task.getJobId());

                // Thuc thi tinh toan tac vu va hien thi tien trinh tung buoc mot
                TaskResult result = TaskExecutor.execute(config.getWorkerId(), task);

                System.out.printf("[%s] >>> [HOAN THANH TASK] Task ID: [%s] | Thoi gian: %d ms | Ket qua: %s%n",
                        config.getWorkerId(), task.getTaskId(), result.getExecutionTimeMs(),
                        result.isSuccess() ? "THANH CONG" : "THAT BAI");
                System.out.printf("[%s]  |-- Dang gui TaskResult ve Master Server (%s:%d)...%n",
                        config.getWorkerId(), config.getMasterHost(), config.getMasterPort());

                // Gui ket qua ve Master
                sendResultToMaster(result);
                System.out.printf("[%s]  \\-- Gui ket qua thanh cong!%n", config.getWorkerId());
                System.out.printf("[%s] =======================================================%n%n", config.getWorkerId());

                // Tra loi phan hoi nhanh qua ket noi hien tai
                Message ack = new Message(MessageType.TASK_RESULT, config.getWorkerId());
                ack.setContent("TASK_ACCEPTED_AND_DONE");
                SocketMessenger.sendMessage(writer, ack);

            } else if (msg.getType() == MessageType.SET_WORKER_STATE) {
                String state = msg.getContent();
                if ("OFF".equalsIgnoreCase(state)) {
                    enabled.set(false);
                    System.out.printf("%n[%s] >>> [TRANG THAI] Nhan lenh OFF tu Master: Chuyen sang che do TAM DUNG (OFFLINE)!%n",
                            config.getWorkerId());
                } else {
                    enabled.set(true);
                    System.out.printf("%n[%s] >>> [TRANG THAI] Nhan lenh ON tu Master: Khoi phuc che do HOAT DONG (ONLINE)!%n",
                            config.getWorkerId());
                    registerWithMaster();
                }
                Message ack = new Message(MessageType.HEARTBEAT_ACK, config.getWorkerId());
                ack.setContent("STATE_UPDATED_" + state);
                SocketMessenger.sendMessage(writer, ack);

            } else if (msg.getType() == MessageType.SHUTDOWN) {
                System.out.printf("[%s] Nhan tin hieu SHUTDOWN tu Master. Dung worker...%n", config.getWorkerId());
                stop();
            }
        } catch (Exception e) {
            System.err.printf("[%s] Loi khi xu ly ket noi: %s%n", config.getWorkerId(), e.getMessage());
        }
    }

    private void sendResultToMaster(TaskResult result) {
        try {
            Message resultMsg = Message.taskResult(config.getWorkerId(), result);
            SocketMessenger.sendOneWay(config.getMasterHost(), config.getMasterPort(), resultMsg, 5000);
        } catch (IOException e) {
            System.err.printf("[%s] Khong the gui ket qua Task [%s] ve Master: %s%n",
                    config.getWorkerId(), result.getTaskId(), e.getMessage());
        }
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);

        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        if (taskThreadPool != null) {
            taskThreadPool.shutdown();
        }
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        System.out.printf("[%s] Da dung hoat dong an toan.%n", config.getWorkerId());
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean en) {
        this.enabled.set(en);
    }

    public WorkerConfig getConfig() {
        return config;
    }
}
