package com.pbl4.worker;

/**
 * Cấu hình kết nối và tài nguyên cho một Worker Node.
 */
public class WorkerConfig {
    private String workerId;
    private String machineName = System.getenv().getOrDefault("COMPUTERNAME", System.getenv().getOrDefault("HOSTNAME", "local-machine"));
    private String machineId = machineName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    private int hostParallelism = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    private String processGroupId = "default";
    private int groupParallelism = hostParallelism;
    private boolean supervised;
    public boolean isSupervised() { return supervised; }
    public void setSupervised(boolean value) { supervised = value; }
    public String getProcessGroupId() { return processGroupId; }
    public void setProcessGroupId(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9._-]{1,64}")) throw new IllegalArgumentException("Invalid ProcessGroupId");
        processGroupId = value;
    }
    public int getGroupParallelism() { return groupParallelism; }
    public void setGroupParallelism(int value) {
        if (value < 1 || value > 1024) throw new IllegalArgumentException("GroupParallelism must be 1-1024");
        groupParallelism = value;
    }
    public String getMachineId() { return machineId; }
    public void setMachineId(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9._-]{1,64}")) throw new IllegalArgumentException("MachineId must contain 1-64 letters, digits, dots, dashes or underscores");
        machineId = value;
    }
    public String getMachineName() { return machineName; }
    public void setMachineName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("MachineName required");
        machineName = value;
    }
    public int getHostParallelism() { return hostParallelism; }
    public void setHostParallelism(int value) {
        if (value < 1 || value > 1024) throw new IllegalArgumentException("HostParallelism must be between 1 and 1024");
        hostParallelism = value;
        if (groupParallelism > value) groupParallelism = value;
    }
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
    private int capacity = 1;
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
