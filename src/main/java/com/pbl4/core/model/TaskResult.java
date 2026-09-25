package com.pbl4.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashMap;
import java.util.Map;

/**
 * Kết quả tính toán của một Task do Worker trả về cho Master.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskResult {
    private String taskId;
    private String jobId;
    private String workerId;
    private boolean success;
    private Map<String, Object> resultData = new HashMap<>();
    private String errorMessage;
    private long executionTimeMs;

    public TaskResult() {
    }

    public TaskResult(String taskId, String jobId, String workerId, boolean success) {
        this.taskId = taskId;
        this.jobId = jobId;
        this.workerId = workerId;
        this.success = success;
    }

    public static TaskResult success(String taskId, String jobId, String workerId) {
        return new TaskResult(taskId, jobId, workerId, true);
    }

    public static TaskResult failure(String taskId, String jobId, String workerId, String error) {
        TaskResult res = new TaskResult(taskId, jobId, workerId, false);
        res.setErrorMessage(error);
        return res;
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

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Map<String, Object> getResultData() {
        return resultData;
    }

    public void setResultData(Map<String, Object> resultData) {
        this.resultData = resultData;
    }

    public TaskResult addResult(String key, Object value) {
        if (this.resultData == null) {
            this.resultData = new HashMap<>();
        }
        this.resultData.put(key, value);
        return this;
    }

    public Object getResultValue(String key) {
        return resultData != null ? resultData.get(key) : null;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    @Override
    public String toString() {
        return "TaskResult{" +
                "taskId='" + taskId + '\'' +
                ", worker='" + workerId + '\'' +
                ", success=" + success +
                ", time=" + executionTimeMs + "ms" +
                '}';
    }
}
