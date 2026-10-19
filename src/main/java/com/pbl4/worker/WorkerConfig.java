package com.pbl4.worker;

/**
 * Cấu hình kết nối và tài nguyên cho một Worker Node.
 */
public class WorkerConfig {
    private String workerId;
    private String bindHost = "0.0.0.0";
    private boolean demoDelay;
    public String getBindHost() { return bindHost; }
    public void setBindHost(String value) { bindHost = value; }
    public boolean isDemoDelay() { return demoDelay; }
    public void setDemoDelay(boolean value) { demoDelay = value; }
    private String host = "127.0.0.1";
    private int port;
    private String masterHost = "127.0.0.1";
    private int masterPort = 50;
    private int capacity = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    private int heartbeatIntervalSeconds = 3;

    public WorkerConfig() {
    }

    public WorkerConfig(String workerId, int port) {
        this.workerId = workerId;
        this.port = port;
    }

    public WorkerConfig(String workerId, String host, int port, String masterHost, int masterPort) {
        this.workerId = workerId;
        this.host = host;
        this.port = port;
        this.masterHost = masterHost;
        this.masterPort = masterPort;
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

    public String getMasterHost() {
        return masterHost;
    }

    public void setMasterHost(String masterHost) {
        this.masterHost = masterHost;
    }

    public int getMasterPort() {
        return masterPort;
    }

    public void setMasterPort(int masterPort) {
        this.masterPort = masterPort;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive"); this.capacity = capacity;
    }

    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }
}
