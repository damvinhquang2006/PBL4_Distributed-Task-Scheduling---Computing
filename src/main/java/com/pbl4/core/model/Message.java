package com.pbl4.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Gói tin thông điệp được truyền qua socket giữa Master và Workers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Message {
    public int protocolVersion = 2;
    public String masterSession;
    public String workerSession;
    public ResourceMetrics metrics;
    private String id;
    private MessageType type;
    private String senderId;
    private String receiverId;
    private Task task;
    private TaskResult taskResult;
    private WorkerInfo workerInfo;
    private String content;
    private long timestamp;

    public Message() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
    }

    public Message(MessageType type, String senderId) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.senderId = senderId;
        this.timestamp = System.currentTimeMillis();
    }

    public static Message register(WorkerInfo info) {
        Message msg = new Message(MessageType.REGISTER_WORKER, info.getWorkerId());
        msg.setWorkerInfo(info);
        return msg;
    }

    public static Message registerAck(String workerId) {
        Message msg = new Message(MessageType.REGISTER_ACK, "MASTER");
        msg.setReceiverId(workerId);
        return msg;
    }

    public static Message assignTask(String workerId, Task task) {
        Message msg = new Message(MessageType.ASSIGN_TASK, "MASTER");
        msg.setReceiverId(workerId);
        msg.setTask(task);
        return msg;
    }

    public static Message taskResult(String workerId, TaskResult result) {
        Message msg = new Message(MessageType.TASK_RESULT, workerId);
        msg.setReceiverId("MASTER");
        msg.setTaskResult(result);
        return msg;
    }

    public static Message heartbeat(String workerId) {
        Message msg = new Message(MessageType.HEARTBEAT, workerId);
        msg.setReceiverId("MASTER");
        return msg;
    }

    public static Message heartbeatAck(String workerId) {
        Message msg = new Message(MessageType.HEARTBEAT_ACK, "MASTER");
        msg.setReceiverId(workerId);
        return msg;
    }

    public static Message setWorkerState(String workerId, boolean online) {
        Message msg = new Message(MessageType.SET_WORKER_STATE, "MASTER");
        msg.setReceiverId(workerId);
        msg.setContent(online ? "ON" : "OFF");
        return msg;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(String receiverId) {
        this.receiverId = receiverId;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public TaskResult getTaskResult() {
        return taskResult;
    }

    public void setTaskResult(TaskResult taskResult) {
        this.taskResult = taskResult;
    }

    public WorkerInfo getWorkerInfo() {
        return workerInfo;
    }

    public void setWorkerInfo(WorkerInfo workerInfo) {
        this.workerInfo = workerInfo;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "Message{" +
                "type=" + type +
                ", sender='" + senderId + '\'' +
                ", receiver='" + receiverId + '\'' +
                '}';
    }
}
