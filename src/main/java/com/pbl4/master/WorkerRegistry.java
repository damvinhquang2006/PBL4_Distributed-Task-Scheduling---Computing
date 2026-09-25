package com.pbl4.master;

import com.pbl4.core.model.WorkerInfo;
import com.pbl4.core.model.WorkerStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Quan ly danh sach cac Worker Node trong cum, theo doi nhip tim (Heartbeat) va phat hien node gap su co.
 */
public class WorkerRegistry {
    private final Map<String, WorkerInfo> workers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService healthCheckExecutor = Executors.newSingleThreadScheduledExecutor();
    private Consumer<WorkerInfo> onWorkerFailedListener;
    private static final long HEARTBEAT_TIMEOUT_MS = 9000; // Qua 9 giay khong co tin nhan coi nhu OFFLINE

    public WorkerRegistry() {
        startHealthChecker();
    }

    public void setOnWorkerFailedListener(Consumer<WorkerInfo> listener) {
        this.onWorkerFailedListener = listener;
    }

    public void register(WorkerInfo info) {
        info.setStatus(WorkerStatus.ONLINE);
        info.setLastHeartbeatTime(System.currentTimeMillis());
        workers.put(info.getWorkerId(), info);
        System.out.printf("[Master - Registry] Dang ky thanh cong Worker: %s%n", info);
    }

    public void recordHeartbeat(String workerId) {
        WorkerInfo info = workers.get(workerId);
        if (info != null) {
            info.setLastHeartbeatTime(System.currentTimeMillis());
            if (info.getStatus() == WorkerStatus.OFFLINE) {
                info.setStatus(WorkerStatus.ONLINE);
                System.out.printf("[Master - Registry] Worker [%s] da khoi phuc truc tuyen (ONLINE)!%n", workerId);
            }
        }
    }

    public List<WorkerInfo> getAvailableWorkers() {
        List<WorkerInfo> list = new ArrayList<>();
        for (WorkerInfo info : workers.values()) {
            if (info.getStatus() == WorkerStatus.ONLINE) {
                list.add(info);
            }
        }
        return list;
    }

    public WorkerInfo getWorker(String workerId) {
        return workers.get(workerId);
    }

    public List<WorkerInfo> getAllWorkers() {
        return new ArrayList<>(workers.values());
    }

    public void setWorkerStatus(String workerId, WorkerStatus status) {
        WorkerInfo info = workers.get(workerId);
        if (info != null) {
            info.setStatus(status);
        }
    }

    private void startHealthChecker() {
        healthCheckExecutor.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (WorkerInfo info : workers.values()) {
                if (info.getStatus() == WorkerStatus.ONLINE) {
                    if (now - info.getLastHeartbeatTime() > HEARTBEAT_TIMEOUT_MS) {
                        info.setStatus(WorkerStatus.OFFLINE);
                        System.err.printf("[Master - Canh bao] Phat hien Worker [%s] mat tin hieu Heartbeat -> Danh dau OFFLINE!%n",
                                info.getWorkerId());
                        if (onWorkerFailedListener != null) {
                            onWorkerFailedListener.accept(info);
                        }
                    }
                }
            }
        }, 3, 2, TimeUnit.SECONDS);
    }

    public void shutdown() {
        healthCheckExecutor.shutdownNow();
    }
}
