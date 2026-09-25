package com.pbl4.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thông tin chi tiết về một Worker Node do Master quản lý.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkerInfo {
    private String workerId;
    private String host;
    private int port;
    private int capacity;
    private WorkerStatus status;
    private long lastHeartbeatTime;
    
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final AtomicInteger completedTasks = new AtomicInteger(0);

    public WorkerInfo() {
        this.status = WorkerStatus.ONLINE;
        this.lastHeartbeatTime = System.currentTimeMillis();
        this.capacity = Runtime.getRuntime().availableProcessors();
    }

    public WorkerInfo(String workerId, String host, int port, int capacity) {
        this.workerId = workerId;
        this.host = host;
        this.port = port;
        this.capacity = capacity > 0 ? capacity : Runtime.getRuntime().availableProcessors();
        this.status = WorkerStatus.ONLINE;
        this.lastHeartbeatTime = System.currentTimeMillis();
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public WorkerStatus getStatus() {
        return status;
    }

    public void setStatus(WorkerStatus status) {
        this.status = status;
    }

    public long getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }

    public void setLastHeartbeatTime(long lastHeartbeatTime) {
        this.lastHeartbeatTime = lastHeartbeatTime;
    }

    public int getActiveTasks() {
        return activeTasks.get();
    }

    public void incrementActiveTasks() {
        activeTasks.incrementAndGet();
    }

    public void decrementActiveTasks() {
        activeTasks.updateAndGet(val -> Math.max(0, val - 1));
    }

    public int getCompletedTasks() {
        return completedTasks.get();
    }

    public void incrementCompletedTasks() {
        completedTasks.incrementAndGet();
    }

    @Override
    public String toString() {
        return String.format("[%s @ %s:%d | Status: %s | Active: %d | Done: %d]",
                workerId, host, port, status, activeTasks.get(), completedTasks.get());
    }
}
