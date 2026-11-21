package com.pbl4.master;

import com.pbl4.core.model.*;
import com.pbl4.master.scheduler.*;
import java.util.*;

/** A single UI view built only from Workers registered in this Master session. */
final class ClusterSnapshot {
    private ClusterSnapshot() { }
    static Map<String, Object> capture(MasterServer master, String session, int port,
                                        WorkerRegistry registry, TaskScheduler scheduler, JobDispatcher dispatcher) {
        Map<String, List<String>> activity = dispatcher.getWorkerActivity();
        List<Map<String, Object>> workers = new ArrayList<>();
        Map<String, Map<String, Object>> machines = new TreeMap<>();
        Map<String, WorkerInfo> latest = new HashMap<>();
        for (WorkerInfo w : registry.getAllWorkers().stream().sorted(Comparator.comparing(WorkerInfo::getWorkerId)).toList()) {
            boolean connected = w.getStatus() != WorkerStatus.OFFLINE;
            boolean ready = scheduler.eligible(w);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workerId", w.getWorkerId()); row.put("displayName", w.displayName);
            row.put("machineId", w.machineId); row.put("machineName", w.machineName);
            row.put("host", w.getHost()); row.put("port", w.getPort()); row.put("capacity", w.getCapacity());
            row.put("hostParallelism", w.hostParallelism); row.put("status", w.getStatus().name()); row.put("connected", connected);
            row.put("processGroupId", w.processGroupId); row.put("groupParallelism", w.groupParallelism); row.put("supervised", w.supervised);
            row.put("paused", w.paused); row.put("quarantined", w.quarantined); row.put("admissionReason", w.admissionReason);
            row.put("activeTasks", w.getActiveTasks()); row.put("completedTasks", w.getCompletedTasks());
            row.put("currentTaskIds", activity.getOrDefault(w.getWorkerId(), List.of()));
            row.put("metrics", w.metrics); row.put("lastHeartbeatTime", w.getLastHeartbeatTime()); row.put("jarSha256", w.jarSha256);
            workers.add(row);
            Map<String, Object> machine = machines.computeIfAbsent(w.machineId, id -> {
                Map<String, Object> group = new LinkedHashMap<>();
                group.put("machineId", id); group.put("machineName", w.machineName); group.put("hostParallelism", w.hostParallelism);
                group.put("effectiveParallelism", scheduler.machineCapacity(id));
                for (String key : List.of("totalWorkers", "connectedWorkers", "readyWorkers", "runningWorkers", "pausedWorkers", "offlineWorkers", "activeTasks", "completedTasks")) group.put(key, 0);
                return group;
            });
            increment(machine, "totalWorkers", 1);
            increment(machine, connected ? "connectedWorkers" : "offlineWorkers", 1);
            increment(machine, "readyWorkers", ready ? 1 : 0);
            increment(machine, "runningWorkers", connected && w.getActiveTasks() > 0 ? 1 : 0);
            increment(machine, "pausedWorkers", connected && w.paused ? 1 : 0);
            increment(machine, "activeTasks", w.getActiveTasks()); increment(machine, "completedTasks", w.getCompletedTasks());
            WorkerInfo previous = latest.get(w.machineId);
            if (previous == null || (connected && previous.getStatus() == WorkerStatus.OFFLINE)
                || (connected == (previous.getStatus() != WorkerStatus.OFFLINE) && w.getLastHeartbeatTime() > previous.getLastHeartbeatTime())) latest.put(w.machineId, w);
        }
        for (Map.Entry<String, Map<String, Object>> entry : machines.entrySet()) {
            WorkerInfo w = latest.get(entry.getKey());
            entry.getValue().put("machineName", w.machineName);
            entry.getValue().put("hostParallelism", w.hostParallelism);
            entry.getValue().put("metrics", w.metrics);
            entry.getValue().put("host", w.getHost());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("masterSession", session); result.put("generatedAt", System.currentTimeMillis());
        result.put("running", master.isRunning()); result.put("masterPort", port); result.put("protocolVersion", Message.CURRENT_VERSION);
        result.put("jarSha256", master.getJarSha256()); result.put("policy", scheduler.getPolicy().name());
        result.put("workers", workers); result.put("machines", new ArrayList<>(machines.values()));
        result.put("totalWorkers", workers.size()); result.put("registeredWorkers", workers.size());
        for (String key : List.of("connectedWorkers", "readyWorkers", "runningWorkers", "pausedWorkers", "offlineWorkers")) {
            result.put(key, machines.values().stream().mapToInt(m -> (int) m.get(key)).sum());
        }
        result.put("availableWorkers", registry.getAvailableWorkers().size());
        result.put("effectiveParallelism", scheduler.effectiveCapacity());
        return result;
    }
    private static void increment(Map<String, Object> map, String key, int amount) { map.put(key, (int) map.get(key) + amount); }
}
