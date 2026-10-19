package com.pbl4.master;

import com.pbl4.core.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class WorkerRegistry {
    private final Map<String, WorkerInfo> workers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService health = Executors.newSingleThreadScheduledExecutor();
    private Consumer<WorkerInfo> onFailure = w -> {};
    public WorkerRegistry() { health.scheduleAtFixedRate(this::checkHealth, 3, 2, TimeUnit.SECONDS); }
    public void setOnWorkerFailedListener(Consumer<WorkerInfo> listener) { onFailure = listener; }
    public synchronized void register(WorkerInfo info) {
        if (info.metrics == null) info.metrics = new ResourceMetrics();
        WorkerInfo old = workers.get(info.getWorkerId());
        if (old != null && Objects.equals(old.sessionId, info.sessionId)) {
            old.setLastHeartbeatTime(System.currentTimeMillis()); old.setStatus(WorkerStatus.ONLINE);
            old.metrics = info.metrics; return;
        }
        if (old != null && old.getStatus() != WorkerStatus.OFFLINE)
            throw new IllegalArgumentException("Worker ID already active: " + info.getWorkerId());
        if (old != null) info.paused = old.paused;
        info.setStatus(WorkerStatus.ONLINE); info.setLastHeartbeatTime(System.currentTimeMillis());
        workers.put(info.getWorkerId(), info);
        System.out.println("[Registry] REGISTER " + info);
    }
    public synchronized boolean heartbeat(String id, String session, ResourceMetrics metrics) {
        WorkerInfo info = workers.get(id);
        if (info == null || !Objects.equals(info.sessionId, session)) return false;
        info.setLastHeartbeatTime(System.currentTimeMillis()); info.setStatus(WorkerStatus.ONLINE);
        info.metrics = metrics == null ? new ResourceMetrics() : metrics;
        return true;
    }
    public void recordHeartbeat(String id) {
        WorkerInfo info = workers.get(id);
        if (info != null) heartbeat(id, info.sessionId, info.metrics);
    }
    public void checkHealth() {
        List<WorkerInfo> failed = new ArrayList<>();
        synchronized (this) {
            for (WorkerInfo w : workers.values()) {
                if (w.getStatus() != WorkerStatus.OFFLINE && System.currentTimeMillis() - w.getLastHeartbeatTime() > 9000) {
                    w.setStatus(WorkerStatus.OFFLINE); failed.add(w);
                }
            }
        }
        failed.forEach(onFailure);
    }
    public List<WorkerInfo> getAvailableWorkers() {
        return workers.values().stream().filter(w -> w.getStatus() != WorkerStatus.OFFLINE && !w.paused).toList();
    }
    public WorkerInfo getWorker(String id) { return workers.get(id); }
    public List<WorkerInfo> getAllWorkers() { return new ArrayList<>(workers.values()); }
    public void setWorkerStatus(String id, WorkerStatus status) {
        WorkerInfo w = workers.get(id); if (w != null) w.setStatus(status);
    }
    public void shutdown() { health.shutdownNow(); }
}
