package com.pbl4.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashMap;
import java.util.Map;

/**
 * Đại diện cho một đơn vị công việc (Task) được Master chia nhỏ và gửi tới Worker.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Task {
    private String taskId;
    private String jobId;
    private TaskType taskType;
    private Map<String, Object> payload = new HashMap<>();
    private String workerAssigned;
    private long createdAt;
    private int retryCount = 0;

    public Task() {
        this.createdAt = System.currentTimeMillis();
    }

    public Task(String taskId, String jobId, TaskType taskType) {
        this.taskId = taskId;
        this.jobId = jobId;
        this.taskType = taskType;
        this.createdAt = System.currentTimeMillis();
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public void setTaskType(TaskType taskType) {
        this.taskType = taskType;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public Task addPayload(String key, Object value) {
        if (this.payload == null) {
            this.payload = new HashMap<>();
        }
        this.payload.put(key, value);
        return this;
    }

    public Object getPayloadValue(String key) {
        return payload != null ? payload.get(key) : null;
    }

    public String getWorkerAssigned() {
        return workerAssigned;
    }

    public void setWorkerAssigned(String workerAssigned) {
        this.workerAssigned = workerAssigned;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    @Override
    public String toString() {
        return "Task{" +
                "taskId='" + taskId + '\'' +
                ", jobId='" + jobId + '\'' +
                ", type=" + taskType +
                ", worker='" + workerAssigned + '\'' +
                '}';
    }
}
