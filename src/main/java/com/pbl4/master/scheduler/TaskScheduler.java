package com.pbl4.master.scheduler;

import com.pbl4.core.model.LoadBalancingPolicy;
import com.pbl4.core.model.Message;
import com.pbl4.core.model.Task;
import com.pbl4.core.model.WorkerInfo;
import com.pbl4.core.network.SocketMessenger;
import com.pbl4.master.WorkerRegistry;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bo lap lich va can bang tai tac vu sang cac Worker Node.
 */
public class TaskScheduler {
    private final WorkerRegistry registry;
    private volatile LoadBalancingPolicy policy = LoadBalancingPolicy.ROUND_ROBIN;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public TaskScheduler(WorkerRegistry registry) {
        this.registry = registry;
    }

    public LoadBalancingPolicy getPolicy() {
        return policy;
    }

    public void setPolicy(LoadBalancingPolicy policy) {
        this.policy = policy;
        System.out.printf("[TaskScheduler] Da doi chinh sach can bang tai sang: %s%n", policy);
    }

    /**
     * Lua chon Worker toi uu dua tren chinh sach can bang tai duoc kich hoat.
     */
    public synchronized WorkerInfo selectWorker() {
        List<WorkerInfo> available = registry.getAvailableWorkers();
        if (available.isEmpty()) {
            return null;
        }

        if (policy == LoadBalancingPolicy.LEAST_LOADED) {
            // Chon worker co so activeTasks nho nhat
            return available.stream()
                    .min(Comparator.comparingInt(WorkerInfo::getActiveTasks))
                    .orElse(available.get(0));
        } else {
            // Mac dinh: ROUND_ROBIN
            int idx = Math.abs(roundRobinCounter.getAndIncrement()) % available.size();
            return available.get(idx);
        }
    }

    /**
     * Giao tac vu cho mot Worker duoc chon qua giao thuc mang Socket TCP.
     */
    public boolean dispatchTask(Task task, WorkerInfo targetWorker) {
        if (targetWorker == null) {
            return false;
        }

        task.setWorkerAssigned(targetWorker.getWorkerId());
        targetWorker.incrementActiveTasks();

        Message assignMsg = Message.assignTask(targetWorker.getWorkerId(), task);

        try {
            // Gui tac vu den cong cua Worker (vi du 51, 52, 53)
            SocketMessenger.sendOneWay(targetWorker.getHost(), targetWorker.getPort(), assignMsg, 5000);
            return true;
        } catch (IOException e) {
            targetWorker.decrementActiveTasks();
            System.err.printf("[TaskScheduler] That bai khi gui Task [%s] toi Worker [%s @ %s:%d]: %s%n",
                    task.getTaskId(), targetWorker.getWorkerId(), targetWorker.getHost(), targetWorker.getPort(), e.getMessage());
            return false;
        }
    }
}
