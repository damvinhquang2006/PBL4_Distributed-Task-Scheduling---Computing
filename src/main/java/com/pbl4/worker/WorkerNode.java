package com.pbl4.worker;

import com.pbl4.core.model.*;
import com.pbl4.core.network.SocketMessenger;
import com.pbl4.worker.executor.TaskExecutor;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class WorkerNode {
    private final WorkerConfig config;
    private final String session = UUID.randomUUID().toString();
    private volatile String masterSession;
    private volatile boolean running, enabled = true, registered;
    private final AtomicInteger active = new AtomicInteger();
    private WorkerRuntime runtime;
    private final boolean ownsRuntime;
    private ScheduledFuture<?> ticker;
    private final AtomicBoolean contacting = new AtomicBoolean();
    private ServerSocket server;
    private final ConcurrentMap<String, Execution> executions = new ConcurrentHashMap<>();
    private long nextContact; private int backoff = 1;
    private static final long RETENTION = 300_000;
    private static class Execution {
        final String attemptId;
        volatile TaskResult result;
        volatile long completedAt;
        volatile boolean acknowledged;
        volatile Thread runner;
        volatile boolean cancelRequested;
        volatile int progress, progressTotal = 1;
        volatile long lastProgressSent;
        final AtomicBoolean released = new AtomicBoolean();
        final AtomicBoolean sending = new AtomicBoolean();
        volatile long lastSendErrorLog;
        Execution(String attemptId) { this.attemptId = attemptId; }
    }
    public WorkerNode(WorkerConfig config) { this(config, null); }
    public WorkerNode(WorkerConfig config, WorkerRuntime sharedRuntime) {
        this.config = config; this.runtime = sharedRuntime; this.ownsRuntime = sharedRuntime == null;
        if (sharedRuntime != null && sharedRuntime.parallelism > config.getHostParallelism())
            throw new IllegalArgumentException("Worker process budget cannot exceed laptop budget");
    }
    public synchronized void start() throws IOException {
        if (running) return;
        server = new ServerSocket();
        try { server.bind(new InetSocketAddress(config.getBindHost(), config.getPort())); }
        catch (IOException | RuntimeException e) { server.close(); throw e; }
        if (ownsRuntime) runtime = new WorkerRuntime(config.getHostParallelism());
        running = true; registered = false;
        Thread listener = new Thread(this::listen, "Worker-" + config.getWorkerId()); listener.setDaemon(true); listener.start();
        ticker = runtime.timer.scheduleWithFixedDelay(() -> {
            if (!running || !contacting.compareAndSet(false, true)) return;
            try { runtime.contacts.submit(() -> { try { maintain(); } finally { contacting.set(false); } }); }
            catch (RejectedExecutionException e) { contacting.set(false); }
        }, 0, 1, TimeUnit.SECONDS);
    }
    private void listen() {
        while (running) {
            try { Socket socket = server.accept(); socket.setSoTimeout(5000); runtime.controls.submit(() -> handle(socket)); }
            catch (IOException | RejectedExecutionException e) { if (running) System.err.println("[Worker] " + e.getMessage()); }
        }
    }
    private Message reply(MessageType type) {
        Message reply = new Message(type, config.getWorkerId()); reply.workerSession = session;
        reply.masterSession = masterSession; reply.metrics = runtime.metrics(active.get()); return reply;
    }
    private void handle(Socket socket) {
        try (socket;
             var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            Message request = SocketMessenger.receiveMessage(reader);
            if (request == null) return;
            if (request.protocolVersion != Message.CURRENT_VERSION) { SocketMessenger.sendMessage(writer, reply(MessageType.TASK_REJECTED)); return; }
            switch (request.getType()) {
                case PING -> SocketMessenger.sendMessage(writer, reply(MessageType.PONG));
                case ASSIGN_TASK -> accept(request, writer);
                case SET_WORKER_STATE -> {
                    if (Objects.equals(masterSession, request.masterSession)) enabled = "ON".equals(request.getContent());
                    SocketMessenger.sendMessage(writer, reply(MessageType.HEARTBEAT_ACK));
                }
                case CANCEL_TASK -> {
                    if (Objects.equals(masterSession, request.masterSession) && Objects.equals(session, request.workerSession)) {
                        boolean stopped = cancelAttempt(request.getContent());
                        Message ack = reply(MessageType.CANCEL_ACK); ack.setContent(stopped ? "STOPPED" : "RUNNING");
                        SocketMessenger.sendMessage(writer, ack);
                    } else SocketMessenger.sendMessage(writer, reply(MessageType.TASK_REJECTED));
                }
                case RESTART_PROCESS_GROUP -> {
                    if (config.isSupervised() && Objects.equals(masterSession, request.masterSession)
                        && Objects.equals(session, request.workerSession)) {
                        SocketMessenger.sendMessage(writer, reply(MessageType.RESTART_ACK));
                        runtime.timer.schedule(() -> System.exit(75), 100, TimeUnit.MILLISECONDS);
                    } else {
                        Message rejected = reply(MessageType.TASK_REJECTED); rejected.setContent("Worker is not supervised");
                        SocketMessenger.sendMessage(writer, rejected);
                    }
                }
                case SHUTDOWN -> { if (Objects.equals(masterSession, request.masterSession)) stop(); }
                default -> { }
            }
        } catch (Exception e) { if (running) System.err.println("[Worker connection] " + e.getMessage()); }
    }
    private synchronized void accept(Message request, BufferedWriter writer) throws IOException {
        Task task = request.getTask();
        if (task == null || task.attemptId == null || !session.equals(task.workerSession)
            || masterSession == null || !masterSession.equals(task.masterSession)) {
            SocketMessenger.sendMessage(writer, reply(MessageType.TASK_REJECTED)); return;
        }
        String key = task.masterSession + "/" + task.attemptId;
        if (executions.containsKey(key)) { SocketMessenger.sendMessage(writer, reply(MessageType.TASK_ACCEPTED)); return; }
        if (!enabled || !running || active.get() >= config.getCapacity()) {
            SocketMessenger.sendMessage(writer, reply(MessageType.TASK_REJECTED)); return;
        }
        if (!runtime.slots.tryAcquire()) {
            Message rejected = reply(MessageType.TASK_REJECTED); rejected.setContent("HOST_CAPACITY");
            SocketMessenger.sendMessage(writer, rejected); return;
        }
        Execution execution = new Execution(task.attemptId); executions.put(key, execution); active.incrementAndGet();
        Runnable calculate = () -> {
            synchronized (execution) {
                if (execution.cancelRequested) { releaseExecution(execution); return; }
                execution.runner = Thread.currentThread();
            }
            TaskExecutor.DEMO_DELAY.set(config.isDemoDelay());
            TaskExecutor.setProgressReporter((completed, total) -> { execution.progress = completed; execution.progressTotal = total; });
            TaskResult result;
            try { result = executeTask(task); }
            catch (RuntimeException e) { result = TaskResult.failure(task.getTaskId(), task.getJobId(), config.getWorkerId(), e.getMessage()); }
            finally {
                TaskExecutor.DEMO_DELAY.remove(); TaskExecutor.clearProgressReporter();
                synchronized (execution) { execution.runner = null; }
                releaseExecution(execution);
            }
            if (execution.cancelRequested) {
                execution.result = null; execution.acknowledged = true; execution.completedAt = System.currentTimeMillis();
                return;
            }
            result.attemptId = task.attemptId; result.masterSession = task.masterSession; result.workerSession = session;
            if (!result.isSuccess()) System.err.printf("[Task failed] %s / %s: %s%n", config.getWorkerId(), task.getTaskId(), result.getErrorMessage());
            execution.completedAt = System.currentTimeMillis(); execution.result = result;
            sendResult(execution);
        };
        // Reserve first, ACK before computation. A lost ACK still executes the deduplicated attempt.
        try { SocketMessenger.sendMessage(writer, reply(MessageType.TASK_ACCEPTED)); }
        finally {
            try { runtime.compute.submit(calculate); }
            catch (RejectedExecutionException e) { releaseExecution(execution); executions.remove(key); }
        }
    }
    private void releaseExecution(Execution execution) {
        if (execution.released.compareAndSet(false, true)) { active.decrementAndGet(); runtime.slots.release(); }
    }
    private void cancelPreviousEpochs(String currentMasterSession) {
        executions.forEach((key, execution) -> {
            if (!key.startsWith(currentMasterSession + "/")) stopExecution(execution, 0);
        });
    }
    private boolean cancelAttempt(String content) {
        if (content == null || content.isBlank()) return true;
        String attemptId = content.split("/", 2)[0];
        Execution execution = executions.values().stream()
            .filter(value -> Objects.equals(value.attemptId, attemptId)).findFirst().orElse(null);
        return execution == null || stopExecution(execution, 900);
    }
    private boolean stopExecution(Execution execution, long waitMillis) {
        Thread runner;
        synchronized (execution) {
            execution.cancelRequested = true;
            runner = execution.runner;
            if (runner == null) {
                execution.acknowledged = true; execution.completedAt = System.currentTimeMillis();
                execution.result = null;
                releaseExecution(execution);
                return true;
            }
            runner.interrupt();
        }
        if (waitMillis > 0) try { runner.join(waitMillis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return !runner.isAlive();
    }
    private void maintain() {
        if (!running) return;
        long now = System.currentTimeMillis();
        if (now >= nextContact) {
            try {
                Message response;
                if (!registered) {
                    WorkerInfo info = new WorkerInfo(config.getWorkerId(), config.getHost(), config.getPort(), config.getCapacity());
                    info.jarSha256 = com.pbl4.core.network.RuntimeBuild.SHA256;
                    info.machineId = config.getMachineId(); info.machineName = config.getMachineName();
                    info.hostParallelism = config.getHostParallelism();
                    info.processGroupId = config.getProcessGroupId(); info.groupParallelism = config.getGroupParallelism();
                    info.supervised = config.isSupervised();
                    info.sessionId = session; info.metrics = runtime.metrics(active.get());
                    Message request = Message.register(info); request.workerSession = session;
                    response = SocketMessenger.sendAndReceive(config.getMasterHost(), config.getMasterPort(), request, 5000);
                    if (response == null || response.protocolVersion != Message.CURRENT_VERSION || response.getType() != MessageType.REGISTER_ACK)
                        throw new IOException(response == null ? "No register ACK" : response.getContent());
                    String previousMaster = masterSession;
                    masterSession = response.masterSession;
                    if (previousMaster != null && !Objects.equals(previousMaster, masterSession)) cancelPreviousEpochs(masterSession);
                    registered = true;
                    enabled = !"PAUSED".equals(response.getContent());
                    System.out.printf("[Worker] REGISTERED %s | laptop=%s (%s) | %s:%d -> Master %s:%d%n",
                        config.getWorkerId(), config.getMachineId(), config.getMachineName(), config.getHost(), config.getPort(), config.getMasterHost(), config.getMasterPort());
                } else {
                    Message request = Message.heartbeat(config.getWorkerId()); request.workerSession = session;
                    request.masterSession = masterSession; request.metrics = runtime.metrics(active.get());
                    response = SocketMessenger.sendAndReceive(config.getMasterHost(), config.getMasterPort(), request, 3000);
                    if (response == null || response.protocolVersion != Message.CURRENT_VERSION || response.getType() != MessageType.HEARTBEAT_ACK
                        || !Objects.equals(masterSession, response.masterSession)) throw new IOException("Registration required");
                    enabled = !"PAUSED".equals(response.getContent());
                }
                backoff = 1; nextContact = now + config.getHeartbeatIntervalSeconds() * 1000L;
            } catch (Exception e) {
                registered = false; nextContact = System.currentTimeMillis() + backoff * 1000L; backoff = Math.min(10, backoff * 2);
                System.err.println("[Worker reconnect] " + config.getWorkerId() + " / " + config.getMachineId() + ": " + e.getMessage());
            }
        }
        executions.entrySet().removeIf(e -> e.getValue().completedAt > 0 && now - e.getValue().completedAt > RETENTION);
        executions.values().forEach(execution -> {
            if (execution.result != null) sendResult(execution);
            else sendProgress(execution);
        });
    }
    private void sendProgress(Execution execution) {
        long now = System.currentTimeMillis();
        if (!running || execution.cancelRequested || now - execution.lastProgressSent < 900) return;
        execution.lastProgressSent = now;
        try {
            Message progress = new Message(MessageType.TASK_PROGRESS, config.getWorkerId());
            progress.masterSession = masterSession; progress.workerSession = session;
            progress.setContent(execution.attemptId + "|" + execution.progress + "|" + execution.progressTotal);
            SocketMessenger.sendAndReceive(config.getMasterHost(), config.getMasterPort(), progress, 1500);
        } catch (IOException ignored) { }
    }
    private void sendResult(Execution execution) {
        if (!running || execution.result == null || execution.acknowledged || !execution.sending.compareAndSet(false, true)) return;
        try {
            runtime.results.submit(() -> {
                try {
                    Message message = Message.taskResult(config.getWorkerId(), execution.result);
                    message.masterSession = execution.result.masterSession; message.workerSession = session;
                    message.metrics = runtime.metrics(active.get());
                    Message ack = SocketMessenger.sendAndReceive(config.getMasterHost(), config.getMasterPort(), message, 3000);
                    if (ack != null && ack.protocolVersion == Message.CURRENT_VERSION && ack.getType() == MessageType.RESULT_ACK) {
                        execution.acknowledged = true;
                        // Retain the deduplication key, but release large matrix results after ACK.
                        execution.result = null;
                    }
                } catch (IOException | RuntimeException e) {
                    long now = System.currentTimeMillis();
                    if (now - execution.lastSendErrorLog >= 10_000) {
                        execution.lastSendErrorLog = now;
                        System.err.printf("[Result retry] %s -> Master %s:%d: %s%n",
                            config.getWorkerId(), config.getMasterHost(), config.getMasterPort(), e.getMessage());
                    }
                }
                finally { execution.sending.set(false); }
            });
        } catch (RejectedExecutionException e) { execution.sending.set(false); }
    }
    public synchronized void stop() {
        if (!running) return; running = false;
        if (ticker != null) ticker.cancel(false);
        if (ownsRuntime) runtime.close();
        registered = false;
        try { server.close(); } catch (IOException ignored) { }
        executions.clear();
    }
    public boolean isRunning() { return running; }
    public boolean isRegistered() { return running && registered; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public WorkerConfig getConfig() { return config; }
    public String getSessionId() { return session; }
    protected TaskResult executeTask(Task task) { return TaskExecutor.execute(config.getWorkerId(), task); }
}
