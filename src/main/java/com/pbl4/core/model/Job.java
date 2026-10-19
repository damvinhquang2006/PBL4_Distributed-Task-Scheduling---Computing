package com.pbl4.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Đại diện cho một bài toán/công việc lớn gồm nhiều sub-tasks.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Job {
    private String jobId;
    private String title;
    private TaskType type;
    private volatile JobStatus status;
    private List<Task> tasks = new ArrayList<>();
    private Map<String, TaskResult> results = new ConcurrentHashMap<>();
    private long startTime;
    private long endTime;
    private Object finalOutput;

    public Job() {
        this.status = JobStatus.SUBMITTED;
        this.startTime = System.currentTimeMillis();
    }

    public Job(String jobId, String title, TaskType type) {
        this.jobId = jobId;
        this.title = title;
        this.type = type;
        this.status = JobStatus.SUBMITTED;
        this.startTime = System.currentTimeMillis();
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public TaskType getType() {
        return type;
    }

    public void setType(TaskType type) {
        this.type = type;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public List<Task> getTasks() {
        return tasks;
    }

    public void setTasks(List<Task> tasks) {
        this.tasks = tasks;
    }

    public void addTask(Task task) {
        this.tasks.add(task);
    }

    public Map<String, TaskResult> getResults() {
        return results;
    }

    public void setResults(Map<String, TaskResult> results) {
        this.results = results;
    }

    public void addResult(TaskResult result) {
        if (result != null) {
            this.results.put(result.getTaskId(), result);
        }
    }

    public boolean isComplete() {
        if (tasks == null || tasks.isEmpty()) return false;
        return tasks.stream().allMatch(t -> results.containsKey(t.getTaskId()) && results.get(t.getTaskId()).isSuccess());
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public long getDurationMs() {
        return (endTime > 0 ? endTime : System.currentTimeMillis()) - startTime;
    }

    public Object getFinalOutput() {
        return finalOutput;
    }

    public void setFinalOutput(Object finalOutput) {
        this.finalOutput = finalOutput;
    }
}
