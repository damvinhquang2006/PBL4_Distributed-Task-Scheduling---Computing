package com.pbl4.master;

import com.pbl4.core.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class WorkerRegistry {
    private final Map<String, WorkerInfo> workers = new ConcurrentHashMap<>();
    private final Map<String, String> persistedAliases = new ConcurrentHashMap<>();
    private final ScheduledExecutorService health = Executors.newSingleThreadScheduledExecutor();
    private Consumer<WorkerInfo> onFailure = w -> {};
    public WorkerRegistry() { health.scheduleAtFixedRate(this::checkHealth, 3, 2, TimeUnit.SECONDS); }
    public void setOnWorkerFailedListener(Consumer<WorkerInfo> listener) { onFailure = listener; }

    public synchronized void loadAliases(Map<String, String> aliases) {
        if (aliases == null) return;
        aliases.forEach((workerId, displayName) -> {
            if (workerId != null && !workerId.isBlank() && displayName != null && !displayName.isBlank()) {
                persistedAliases.put(workerId, displayName);
                WorkerInfo existing = workers.get(workerId);
                if (existing != null) existing.displayName = displayName;
            }
        });
    }

    public synchronized boolean setAlias(String workerId, String displayName) {
        if (workerId == null || workerId.isBlank() || displayName == null || displayName.isBlank()) return false;
        persistedAliases.put(workerId, displayName);
        WorkerInfo existing = workers.get(workerId);
        if (existing != null) existing.displayName = displayName;
        return true;
    }

    public String getAlias(String workerId) {
        return workerId == null ? null : persistedAliases.get(workerId);
    }

    public synchronized void register(WorkerInfo info) {
        if (info.machineId == null || info.machineId.isBlank() || info.machineName == null || info.machineName.isBlank() || info.hostParallelism < 1)
            throw new IllegalArgumentException("Laptop identity and HostParallelism required; update both laptops to the same new JAR");
        if (workers.values().stream().anyMatch(w -> w.getStatus() != WorkerStatus.OFFLINE && info.machineId.equals(w.machineId) && info.hostParallelism != w.hostParallelism))
            throw new IllegalArgumentException("Conflicting HostParallelism for laptop " + info.machineId);
        if (info.metrics == null) info.metrics = new ResourceMetrics();
        if (info.groupParallelism < 1) info.groupParallelism = info.hostParallelism;

        WorkerInfo old = workers.get(info.getWorkerId());
        if (old != null && Objects.equals(old.sessionId, info.sessionId)) {
            old.setLastHeartbeatTime(System.currentTimeMillis());
            old.setStatus(WorkerStatus.ONLINE);
            old.metrics = info.metrics;
            String alias = persistedAliases.get(info.getWorkerId());
            if (alias != null && !alias.isBlank()) old.displayName = alias;
            return;
        }
        if (old != null && old.getStatus() != WorkerStatus.OFFLINE) {
            boolean sameEndpoint = Objects.equals(old.getHost(), info.getHost()) && old.getPort() == info.getPort()
                && Objects.equals(old.machineId, info.machineId);
            if (!sameEndpoint) throw new IllegalArgumentException("Worker ID already active: " + info.getWorkerId());
            old.setStatus(WorkerStatus.OFFLINE);
            onFailure.accept(old);
        }
        if (old != null) {
            info.paused = old.paused;
        } else {
            info.paused = info.startPaused;
        }

        String alias = persistedAliases.get(info.getWorkerId());
        if (alias != null && !alias.isBlank()) {
            info.displayName = alias;
        } else if (old != null && old.displayName != null && !old.displayName.isBlank()) {
            info.displayName = old.displayName;
        } else if (info.displayName == null || info.displayName.isBlank()) {
            info.displayName = info.getWorkerId();
        }

        info.setStatus(WorkerStatus.ONLINE);
        info.setLastHeartbeatTime(System.currentTimeMillis());
        workers.put(info.getWorkerId(), info);
        System.out.println("[Registry] REGISTER " + info + " | laptop=" + info.machineId + " (" + info.machineName + ")");
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

    public List<String> markWorkersOffline(Collection<String> workerIds) {
        if (workerIds == null || workerIds.isEmpty()) return List.of();
        List<WorkerInfo> newlyFailed = new ArrayList<>();
        List<String> offlineIds = new ArrayList<>();
        synchronized (this) {
            for (String id : workerIds) {
                if (id == null || id.isBlank()) continue;
                WorkerInfo w = workers.get(id);
                if (w != null) {
                    if (w.getStatus() != WorkerStatus.OFFLINE) {
                        w.setStatus(WorkerStatus.OFFLINE);
                        newlyFailed.add(w);
                    }
                    offlineIds.add(id);
                }
            }
        }
        newlyFailed.forEach(onFailure);
        return offlineIds;
    }

    public void shutdown() { health.shutdownNow(); }
}
