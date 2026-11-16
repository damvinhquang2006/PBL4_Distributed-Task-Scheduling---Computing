package com.pbl4.master.scheduler;

import com.pbl4.core.model.*;
import com.pbl4.core.network.SocketMessenger;
import com.pbl4.master.WorkerRegistry;
import java.io.IOException;
import java.util.*;

public class TaskScheduler {
    private final WorkerRegistry registry;
    private volatile LoadBalancingPolicy policy = LoadBalancingPolicy.ROUND_ROBIN;
    private volatile ResourceThresholds thresholds = ResourceThresholds.defaults();
    private String lastMachine = "";
    private final Map<String, String> lastWorker = new HashMap<>();
    private final Map<String, Integer> reservedMachines = new HashMap<>();
    private static class Gate { boolean cpu, ram, heap; }
    private final Map<String, Gate> gates = new HashMap<>();
    public TaskScheduler(WorkerRegistry registry) { this.registry = registry; }
    public LoadBalancingPolicy getPolicy() { return policy; }
    public void setPolicy(LoadBalancingPolicy value) { policy = Objects.requireNonNull(value); }
    public void setThresholds(ResourceThresholds value) { thresholds = Objects.requireNonNull(value); }
    private boolean blocked(boolean old, Double value, double high, double low) {
        value = ResourceMetrics.valid(value);
        return value != null && (old ? value >= low : value >= high);
    }
    public synchronized boolean eligible(WorkerInfo w) {
        ResourceMetrics m = w.metrics == null ? new ResourceMetrics() : w.metrics;
        ResourceMetrics host = registry.getAllWorkers().stream()
            .filter(other -> machine(other).equals(machine(w)) && other.getStatus() != WorkerStatus.OFFLINE && other.metrics != null)
            .max(Comparator.comparingLong(WorkerInfo::getLastHeartbeatTime)).map(other -> other.metrics).orElse(m);
        ResourceThresholds t = thresholds;
        Gate gate = gates.computeIfAbsent(machine(w), id -> new Gate());
        gate.cpu = blocked(gate.cpu, host.cpu, t.cpuHigh(), t.cpuLow());
        gate.ram = blocked(gate.ram, host.ram, t.ramHigh(), t.ramLow());
        gate.heap = blocked(gate.heap, host.heap, t.heapHigh(), t.heapLow());
        w.cpuBlocked = gate.cpu; w.ramBlocked = gate.ram; w.heapBlocked = gate.heap;
        String reason = w.getStatus() == WorkerStatus.OFFLINE ? "OFFLINE" : w.paused ? "PAUSED" :
            w.quarantined ? "QUARANTINED" :
            w.unavailableUntil > System.currentTimeMillis() ? "RETRY_BACKOFF" :
            Math.max(w.getActiveTasks(), m.runningTasks) >= w.getCapacity() ? "CAPACITY" :
            groupActive(group(w)) >= groupCapacity(group(w)) ? "HOST_CAPACITY" :
            machineActive(machine(w)) >= machineCapacity(machine(w)) ? "HOST_CAPACITY" :
            policy == LoadBalancingPolicy.RESOURCE_AWARE && w.cpuBlocked ? "CPU_HIGH" :
            policy == LoadBalancingPolicy.RESOURCE_AWARE && w.ramBlocked ? "RAM_HIGH" :
            policy == LoadBalancingPolicy.RESOURCE_AWARE && w.heapBlocked ? "HEAP_HIGH" : "READY";
        w.admissionReason = reason;
        return reason.equals("READY");
    }
    private static String machine(WorkerInfo w) { return w.machineId == null ? w.getWorkerId() : w.machineId; }
    private static String group(WorkerInfo w) {
        return machine(w) + "/" + (w.processGroupId == null || w.processGroupId.isBlank() ? "default" : w.processGroupId);
    }
    private int groupCapacity(String id) {
        return registry.getAllWorkers().stream().filter(w -> w.getStatus() != WorkerStatus.OFFLINE && group(w).equals(id))
            .mapToInt(w -> w.groupParallelism > 0 ? w.groupParallelism : w.hostParallelism).min().orElse(0);
    }
    public synchronized void quarantineGroup(WorkerInfo member, boolean quarantined) {
        for (WorkerInfo worker : registry.getAllWorkers()) if (group(worker).equals(group(member))) {
            worker.quarantined = quarantined;
            worker.quarantinedAtMillis = quarantined ? (worker.quarantinedAtMillis == 0 ? System.currentTimeMillis() : worker.quarantinedAtMillis) : 0;
            worker.quarantineSinceNanos = quarantined ? (worker.quarantineSinceNanos == 0 ? System.nanoTime() : worker.quarantineSinceNanos) : 0;
            if (!quarantined) { worker.groupRestartRequested = false; worker.groupRestartAttemptedAtMillis = 0; }
        }
    }
    public synchronized void markGroupRestartRequested(WorkerInfo member) {
        for (WorkerInfo worker : registry.getAllWorkers()) if (group(worker).equals(group(member))) {
            if (!worker.groupRestartRequested) {
                worker.groupRestartRequested = true; worker.groupRestartAttemptedAtMillis = System.currentTimeMillis();
            }
        }
    }
    public synchronized void clearGroupRestartRequested(WorkerInfo member) {
        for (WorkerInfo worker : registry.getAllWorkers()) if (group(worker).equals(group(member))) worker.groupRestartRequested = false;
    }
    private int groupActive(String id) {
        List<WorkerInfo> members = registry.getAllWorkers().stream().filter(w -> w.getStatus() != WorkerStatus.OFFLINE && group(w).equals(id)).toList();
        int reserved = members.stream().mapToInt(WorkerInfo::getActiveTasks).sum();
        int reported = members.stream().filter(w -> w.metrics != null).mapToInt(w -> w.metrics.hostRunningTasks).max().orElse(0);
        return Math.max(reserved, reported);
    }
    public synchronized int machineCapacity(String id) {
        List<WorkerInfo> members = registry.getAllWorkers().stream().filter(w -> w.getStatus() != WorkerStatus.OFFLINE && machine(w).equals(id)).toList();
        int capacity = members.stream().mapToInt(WorkerInfo::getCapacity).sum();
        int limit = members.stream().mapToInt(w -> w.hostParallelism > 0 ? w.hostParallelism : w.getCapacity()).min().orElse(0);
        Map<String, Integer> groupBudgets = new HashMap<>();
        members.forEach(w -> groupBudgets.merge(group(w), w.groupParallelism > 0 ? w.groupParallelism : w.hostParallelism, Math::min));
        int groupLimit = groupBudgets.values().stream().mapToInt(Integer::intValue).sum();
        return Math.min(capacity, Math.min(limit, groupLimit));
    }
    public synchronized int machineActive(String id) {
        List<WorkerInfo> members = registry.getAllWorkers().stream().filter(w -> w.getStatus() != WorkerStatus.OFFLINE && machine(w).equals(id)).toList();
        int tracked = members.stream().mapToInt(WorkerInfo::getActiveTasks).sum();
        int reported = members.stream().filter(w -> w.metrics != null).mapToInt(w -> w.metrics.hostRunningTasks).max().orElse(0);
        return Math.max(Math.max(tracked, reported), reservedMachines.getOrDefault(id, 0));
    }
    public synchronized int effectiveCapacity() {
        List<WorkerInfo> available = registry.getAvailableWorkers();
        return available.stream().map(TaskScheduler::machine).distinct()
            .mapToInt(id -> Math.min(machineCapacity(id), available.stream().filter(w -> machine(w).equals(id)).mapToInt(WorkerInfo::getCapacity).sum())).sum();
    }
    public synchronized String describeAvailability() {
        List<WorkerInfo> workers = registry.getAllWorkers();
        if (workers.isEmpty()) return "Chưa có Worker đăng ký với Master.";
        return workers.stream().sorted(Comparator.comparing(WorkerInfo::getWorkerId)).map(w -> {
            eligible(w);
            return w.getWorkerId() + " (" + w.getHost() + ":" + w.getPort() + "): " + w.admissionReason;
        }).collect(java.util.stream.Collectors.joining("; "));
    }
    public synchronized WorkerInfo selectWorker() {
        List<WorkerInfo> available = registry.getAvailableWorkers().stream().filter(this::eligible)
            .sorted(Comparator.comparing(WorkerInfo::getWorkerId)).toList();
        if (available.isEmpty()) return null;
        List<String> ids = available.stream().map(TaskScheduler::machine).distinct().sorted().toList();
        double best = ids.stream().mapToDouble(id -> (double) machineActive(id) / machineCapacity(id)).min().orElseThrow();
        List<String> tied = ids.stream().filter(id -> Math.abs((double) machineActive(id) / machineCapacity(id) - best) < 1e-9).toList();
        String chosen = tied.stream().filter(id -> id.compareTo(lastMachine) > 0).findFirst().orElse(tied.get(0));
        lastMachine = chosen;
        List<WorkerInfo> members = available.stream().filter(w -> machine(w).equals(chosen)).toList();
        if (policy == LoadBalancingPolicy.LEAST_LOADED) {
            double load = members.stream().mapToDouble(w -> (double) w.getActiveTasks() / w.getCapacity()).min().orElseThrow();
            members = members.stream().filter(w -> Math.abs((double) w.getActiveTasks() / w.getCapacity() - load) < 1e-9).toList();
        }
        String previous = lastWorker.getOrDefault(chosen, "");
        WorkerInfo worker = members.stream().filter(w -> w.getWorkerId().compareTo(previous) > 0).findFirst().orElse(members.get(0));
        lastWorker.put(chosen, worker.getWorkerId());
        return worker;
    }
    /** Selection and reservation share one lock across all jobs. */
    public synchronized WorkerInfo reserveWorker() {
        WorkerInfo worker = selectWorker();
        if (worker != null) {
            worker.incrementActiveTasks();
            reservedMachines.merge(machine(worker), 1, Integer::sum);
        }
        return worker;
    }
    public synchronized void release(WorkerInfo worker) {
        worker.decrementActiveTasks();
        reservedMachines.computeIfPresent(machine(worker), (id, count) -> Math.max(0, count - 1));
    }
    public Message sendReservedTask(Task task, WorkerInfo target) throws IOException {
        Message request = Message.assignTask(target.getWorkerId(), task);
        request.masterSession = task.masterSession; request.workerSession = task.workerSession;
        // Retry an ambiguous delivery using the SAME attempt ID; the Worker deduplicates it.
        IOException failure = null;
        for (int i = 0; i < 2; i++) {
            try {
                Message reply = SocketMessenger.sendAndReceive(target.getHost(), target.getPort(), request, 5000);
                if (reply == null || reply.protocolVersion != Message.CURRENT_VERSION
                    || !Objects.equals(reply.workerSession, task.workerSession)
                    || !Objects.equals(reply.masterSession, task.masterSession)) throw new IOException("Missing/incompatible task ACK");
                return reply;
            } catch (IOException e) { failure = e; }
        }
        throw failure;
    }
}
