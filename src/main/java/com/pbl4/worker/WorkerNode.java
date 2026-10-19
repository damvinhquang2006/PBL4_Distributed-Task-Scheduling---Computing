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
    private final ResourceSampler sampler = new ResourceSampler();
    private ServerSocket server;
    private ExecutorService controls, compute, resultSender;
    private ScheduledExecutorService maintenance;
    private final ConcurrentMap<String, Execution> executions = new ConcurrentHashMap<>();
    private long nextContact; private int backoff = 1;
    private static final long RETENTION = 300_000;
    private static class Execution {
        volatile TaskResult result;
        volatile long completedAt;
        volatile boolean acknowledged;
        final AtomicBoolean sending = new AtomicBoolean();
    }
    public WorkerNode(WorkerConfig config) { this.config = config; }
    public synchronized void start() throws IOException {
        if (running) return;
        server = new ServerSocket();
        try { server.bind(new InetSocketAddress(config.getBindHost(), config.getPort())); }
        catch (IOException | RuntimeException e) { server.close(); throw e; }
        controls = Executors.newCachedThreadPool(); compute = Executors.newFixedThreadPool(config.getCapacity());
        resultSender = Executors.newFixedThreadPool(2); maintenance = Executors.newScheduledThreadPool(2);
        running = true;
        Thread listener = new Thread(this::listen, "Worker-" + config.getWorkerId()); listener.setDaemon(true); listener.start();
        sampler.sample();
        maintenance.scheduleAtFixedRate(sampler::sample, 1, 1, TimeUnit.SECONDS);
        maintenance.scheduleWithFixedDelay(this::maintain, 0, 1, TimeUnit.SECONDS);
    }
    private void listen() {
        while (running) {
            try { Socket socket = server.accept(); socket.setSoTimeout(5000); controls.submit(() -> handle(socket)); }
            catch (IOException | RejectedExecutionException e) { if (running) System.err.println("[Worker] " + e.getMessage()); }
        }
    }
    private Message reply(MessageType type) {
        Message reply = new Message(type, config.getWorkerId()); reply.workerSession = session;
        reply.masterSession = masterSession; reply.metrics = sampler.current(active.get()); return reply;
    }
    private void handle(Socket socket) {
        try (socket;
             var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            Message request = SocketMessenger.receiveMessage(reader);
            if (request == null) return;
            if (request.protocolVersion != 2) { SocketMessenger.sendMessage(writer, reply(MessageType.TASK_REJECTED)); return; }
            switch (request.getType()) {
                case PING -> SocketMessenger.sendMessage(writer, reply(MessageType.PONG));
                case ASSIGN_TASK -> accept(request, writer);
                case SET_WORKER_STATE -> {
                    if (Objects.equals(masterSession, request.masterSession)) enabled = "ON".equals(request.getContent());
                    SocketMessenger.sendMessage(writer, reply(MessageType.HEARTBEAT_ACK));
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
        Execution execution = new Execution(); executions.put(key, execution); active.incrementAndGet();
        compute.submit(() -> {
            TaskExecutor.DEMO_DELAY.set(config.isDemoDelay());
            TaskResult result;
            try { result = executeTask(task); }
            finally { TaskExecutor.DEMO_DELAY.remove(); active.decrementAndGet(); }
            result.attemptId = task.attemptId; result.masterSession = task.masterSession; result.workerSession = session;
            execution.completedAt = System.currentTimeMillis(); execution.result = result;
            sendResult(execution);
        });
        // Acceptance is acknowledged before computation finishes. A lost ACK is safe to retry.
        SocketMessenger.sendMessage(writer, reply(MessageType.TASK_ACCEPTED));
    }
    private void maintain() {
        if (!running) return;
        long now = System.currentTimeMillis();
        if (now >= nextContact) {
            try {
                Message response;
                if (!registered) {
                    WorkerInfo info = new WorkerInfo(config.getWorkerId(), config.getHost(), config.getPort(), config.getCapacity());
                    info.sessionId = session; info.metrics = sampler.current(active.get());
                    Message request = Message.register(info); request.workerSession = session;
                    response = SocketMessenger.sendAndReceive(config.getMasterHost(), config.getMasterPort(), request, 5000);
                    if (response == null || response.protocolVersion != 2 || response.getType() != MessageType.REGISTER_ACK)
                        throw new IOException(response == null ? "No register ACK" : response.getContent());
                    masterSession = response.masterSession; registered = true;
                    enabled = !"PAUSED".equals(response.getContent());
                    System.out.println("[Worker] REGISTERED " + config.getWorkerId() + " -> " + config.getMasterHost());
                } else {
                    Message request = Message.heartbeat(config.getWorkerId()); request.workerSession = session;
                    request.masterSession = masterSession; request.metrics = sampler.current(active.get());
                    response = SocketMessenger.sendAndReceive(config.getMasterHost(), config.getMasterPort(), request, 3000);
                    if (response == null || response.protocolVersion != 2 || response.getType() != MessageType.HEARTBEAT_ACK
                        || !Objects.equals(masterSession, response.masterSession)) throw new IOException("Registration required");
                    enabled = !"PAUSED".equals(response.getContent());
                }
                backoff = 1; nextContact = now + config.getHeartbeatIntervalSeconds() * 1000L;
            } catch (Exception e) {
                registered = false; nextContact = System.currentTimeMillis() + backoff * 1000L; backoff = Math.min(10, backoff * 2);
                System.err.println("[Worker reconnect] " + e.getMessage());
            }
        }
        executions.entrySet().removeIf(e -> e.getValue().completedAt > 0 && now - e.getValue().completedAt > RETENTION);
        executions.values().forEach(this::sendResult);
    }
    private void sendResult(Execution execution) {
        if (!running || execution.result == null || execution.acknowledged || !execution.sending.compareAndSet(false, true)) return;
        try {
            resultSender.submit(() -> {
                try {
                    Message message = Message.taskResult(config.getWorkerId(), execution.result);
                    message.masterSession = execution.result.masterSession; message.workerSession = session;
                    message.metrics = sampler.current(active.get());
                    Message ack = SocketMessenger.sendAndReceive(config.getMasterHost(), config.getMasterPort(), message, 3000);
                    if (ack != null && ack.protocolVersion == 2 && ack.getType() == MessageType.RESULT_ACK) {
                        execution.acknowledged = true;
                        // Retain the deduplication key, but release large matrix results after ACK.
                        execution.result = null;
                    }
                } catch (IOException ignored) { }
                finally { execution.sending.set(false); }
            });
        } catch (RejectedExecutionException e) { execution.sending.set(false); }
    }
    public synchronized void stop() {
        if (!running) return; running = false;
        maintenance.shutdownNow(); controls.shutdownNow(); compute.shutdownNow(); resultSender.shutdownNow();
        try { server.close(); } catch (IOException ignored) { }
        executions.clear();
    }
    public boolean isRunning() { return running; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public WorkerConfig getConfig() { return config; }
    public String getSessionId() { return session; }
    protected TaskResult executeTask(Task task) { return TaskExecutor.execute(config.getWorkerId(), task); }
}
