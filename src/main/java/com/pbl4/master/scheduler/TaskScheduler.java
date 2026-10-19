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
    private int cursor;
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
        ResourceThresholds t = thresholds;
        w.cpuBlocked = blocked(w.cpuBlocked, m.cpu, t.cpuHigh(), t.cpuLow());
        w.ramBlocked = blocked(w.ramBlocked, m.ram, t.ramHigh(), t.ramLow());
        w.heapBlocked = blocked(w.heapBlocked, m.heap, t.heapHigh(), t.heapLow());
        String reason = w.getStatus() == WorkerStatus.OFFLINE ? "OFFLINE" : w.paused ? "PAUSED" :
            w.unavailableUntil > System.currentTimeMillis() ? "RETRY_BACKOFF" :
            Math.max(w.getActiveTasks(), m.runningTasks) >= w.getCapacity() ? "CAPACITY" :
            policy == LoadBalancingPolicy.RESOURCE_AWARE && w.cpuBlocked ? "CPU_HIGH" :
            policy == LoadBalancingPolicy.RESOURCE_AWARE && w.ramBlocked ? "RAM_HIGH" :
            policy == LoadBalancingPolicy.RESOURCE_AWARE && w.heapBlocked ? "HEAP_HIGH" : "READY";
        w.admissionReason = reason;
        return reason.equals("READY");
    }
    private static double metric(Double value) { return ResourceMetrics.valid(value) == null ? 101 : value; }
    public synchronized WorkerInfo selectWorker() {
        List<WorkerInfo> available = registry.getAvailableWorkers().stream().filter(this::eligible)
            .sorted(Comparator.comparing(WorkerInfo::getWorkerId)).toList();
        if (available.isEmpty()) return null;
        if (policy == LoadBalancingPolicy.ROUND_ROBIN)
            return available.get(Math.floorMod(cursor++, available.size()));
        Comparator<WorkerInfo> comparator = policy == LoadBalancingPolicy.LEAST_LOADED ?
            Comparator.comparingDouble(WorkerInfo::getActiveTasks) :
            Comparator.comparingDouble(w -> (double) Math.max(w.getActiveTasks(), w.metrics.runningTasks) / w.getCapacity());
        return available.stream().min(comparator
            .thenComparingDouble(w -> metric(w.metrics.cpu)).thenComparingDouble(w -> metric(w.metrics.ram))
            .thenComparing(WorkerInfo::getWorkerId)).orElse(null);
    }
    /** Selection and reservation share one lock across all jobs. */
    public synchronized WorkerInfo reserveWorker() {
        WorkerInfo worker = selectWorker();
        if (worker != null) worker.incrementActiveTasks();
        return worker;
    }
    public synchronized void release(WorkerInfo worker) { worker.decrementActiveTasks(); }
    public Message sendReservedTask(Task task, WorkerInfo target) throws IOException {
        Message request = Message.assignTask(target.getWorkerId(), task);
        request.masterSession = task.masterSession; request.workerSession = task.workerSession;
        // Retry an ambiguous delivery using the SAME attempt ID; the Worker deduplicates it.
        IOException failure = null;
        for (int i = 0; i < 2; i++) {
            try {
                Message reply = SocketMessenger.sendAndReceive(target.getHost(), target.getPort(), request, 5000);
                if (reply == null || reply.protocolVersion != 2
                    || !Objects.equals(reply.workerSession, task.workerSession)
                    || !Objects.equals(reply.masterSession, task.masterSession)) throw new IOException("Missing/incompatible task ACK");
                return reply;
            } catch (IOException e) { failure = e; }
        }
        throw failure;
    }
}
