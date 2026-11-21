package com.pbl4.app;

import com.pbl4.worker.*;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Starts logical Workers with one laptop-wide compute and resource budget. */
public class WorkerClusterLauncher {
    public static final class Cluster implements AutoCloseable {
        private final List<WorkerNode> workers;
        private final WorkerRuntime runtime;
        private Cluster(List<WorkerNode> workers, WorkerRuntime runtime) { this.workers = List.copyOf(workers); this.runtime = runtime; }
        public List<WorkerNode> getWorkers() { return workers; }
        public int registeredCount() { return (int) workers.stream().filter(WorkerNode::isRegistered).count(); }
        public int runningTasks() { return runtime.getRunningTasks(); }
        public void watchSupervisor(Path ownershipFile, long supervisorPid, String supervisorToken) {
            AtomicInteger stopped = new AtomicInteger();
            runtime.timerForStatus(() -> {
                if (stopped.get() == 0 && !SupervisorLease.isOwnedBy(ownershipFile, supervisorPid, supervisorToken)
                    && stopped.compareAndSet(0, 1)) {
                    System.err.println("[WorkerCluster] Supervisor lease lost; stopping orphaned process group.");
                    close(); System.exit(0);
                }
            });
        }
        @Override public void close() { workers.forEach(WorkerNode::stop); runtime.close(); }
    }

    public static void main(String[] args) throws Exception {
        WorkerConfig defaults = new WorkerConfig();
        int count = 50, startPort = 5001, masterPort = 5000, capacity = 1, parallelism = defaults.getHostParallelism();
        int runtimeParallelism = -1, workerOffset = 0, groupParallelism = -1;
        String host = "127.0.0.1", masterHost = "127.0.0.1", prefix = "worker-", bindHost = "0.0.0.0";
        String machineId = defaults.getMachineId(), machineName = defaults.getMachineName();
        String processGroupId = null, supervisorLock = null, supervisorToken = null, supervisorPid = null;
        boolean requireAll = false, startPaused = false;
        for (int i = 0; i < args.length; i++) {
            if ("--requireAll".equals(args[i])) { requireAll = true; continue; }
            if ("--startPaused".equals(args[i])) { startPaused = true; continue; }
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + args[i]);
            String value = args[++i];
            switch (args[i - 1]) {
                case "--count" -> count = Integer.parseInt(value);
                case "--startPort" -> startPort = Integer.parseInt(value);
                case "--host" -> host = value;
                case "--bindHost" -> bindHost = value;
                case "--masterHost" -> masterHost = value;
                case "--masterPort" -> masterPort = Integer.parseInt(value);
                case "--capacity" -> capacity = Integer.parseInt(value);
                case "--hostParallelism" -> parallelism = Integer.parseInt(value);
                case "--runtimeParallelism" -> runtimeParallelism = Integer.parseInt(value);
                case "--workerOffset" -> workerOffset = Integer.parseInt(value);
                case "--groupParallelism" -> groupParallelism = Integer.parseInt(value);
                case "--processGroupId" -> processGroupId = value;
                case "--supervisorLock" -> supervisorLock = value;
                case "--supervisorPid" -> supervisorPid = value;
                case "--supervisorToken" -> supervisorToken = value;
                case "--idPrefix" -> prefix = value;
                case "--machineId" -> machineId = value;
                case "--machineName" -> machineName = value;
                default -> throw new IllegalArgumentException("Unknown option " + args[i - 1]);
            }
        }
        if (runtimeParallelism < 0) runtimeParallelism = parallelism;
        if (groupParallelism < 0) groupParallelism = runtimeParallelism;
        if (processGroupId == null) processGroupId = machineId + "-group-01";
        Cluster cluster = launch(count, startPort, host, masterHost, masterPort, capacity, prefix, machineId, machineName, bindHost,
            parallelism, runtimeParallelism, workerOffset, processGroupId, groupParallelism, requireAll, supervisorLock != null, startPaused);
        Runtime.getRuntime().addShutdownHook(new Thread(cluster::close));
        if (supervisorLock != null) {
            if (supervisorPid == null || supervisorToken == null || supervisorToken.isBlank())
                throw new IllegalArgumentException("Incomplete Worker supervisor lease arguments");
            cluster.watchSupervisor(Path.of(supervisorLock), Long.parseLong(supervisorPid), supervisorToken);
        }
    }

    public static Cluster launch(int count, int startPort, String host, String masterHost, int masterPort,
                                 int capacity, String prefix, String machineId, String machineName,
                                 String bindHost, int parallelism) throws IOException {
        return launch(count, startPort, host, masterHost, masterPort, capacity, prefix, machineId, machineName, bindHost,
            parallelism, parallelism, 0, machineId + "-group-01", parallelism);
    }

    public static Cluster launch(int count, int startPort, String host, String masterHost, int masterPort,
                                 int capacity, String prefix, String machineId, String machineName, String bindHost,
                                 int hostParallelism, int runtimeParallelism, int workerOffset,
                                 String processGroupId, int groupParallelism) throws IOException {
        return launch(count, startPort, host, masterHost, masterPort, capacity, prefix, machineId, machineName, bindHost,
            hostParallelism, runtimeParallelism, workerOffset, processGroupId, groupParallelism, false);
    }

    public static Cluster launch(int count, int startPort, String host, String masterHost, int masterPort,
                                 int capacity, String prefix, String machineId, String machineName, String bindHost,
                                 int hostParallelism, int runtimeParallelism, int workerOffset,
                                 String processGroupId, int groupParallelism, boolean requireAll) throws IOException {
        return launch(count, startPort, host, masterHost, masterPort, capacity, prefix, machineId, machineName, bindHost,
            hostParallelism, runtimeParallelism, workerOffset, processGroupId, groupParallelism, requireAll, false);
    }

    public static Cluster launch(int count, int startPort, String host, String masterHost, int masterPort,
                                 int capacity, String prefix, String machineId, String machineName, String bindHost,
                                 int hostParallelism, int runtimeParallelism, int workerOffset,
                                 String processGroupId, int groupParallelism, boolean requireAll, boolean supervised) throws IOException {
        return launch(count, startPort, host, masterHost, masterPort, capacity, prefix, machineId, machineName, bindHost,
            hostParallelism, runtimeParallelism, workerOffset, processGroupId, groupParallelism, requireAll, supervised, false);
    }

    public static Cluster launch(int count, int startPort, String host, String masterHost, int masterPort,
                                 int capacity, String prefix, String machineId, String machineName, String bindHost,
                                 int hostParallelism, int runtimeParallelism, int workerOffset,
                                 String processGroupId, int groupParallelism, boolean requireAll, boolean supervised, boolean startPaused) throws IOException {
        if (count < 1 || count > 1024 || startPort < 1 || (long) startPort + count - 1 > 65535 || masterPort < 1 || masterPort > 65535 || capacity < 1)
            throw new IllegalArgumentException("Invalid worker count, port range or capacity");
        if (hostParallelism < 1 || runtimeParallelism < 1 || runtimeParallelism > hostParallelism
            || groupParallelism != runtimeParallelism || groupParallelism > hostParallelism || workerOffset < 0)
            throw new IllegalArgumentException("Invalid host/process group compute budget");
        WorkerConfig validation = new WorkerConfig();
        validation.setMachineId(machineId); validation.setMachineName(machineName); validation.setHostParallelism(hostParallelism);
        InetAddress address = InetAddress.getByName(host), master = InetAddress.getByName(masterHost);
        if (master.isAnyLocalAddress()) throw new IllegalArgumentException("MasterAddress must be the Master laptop IP, not 0.0.0.0");
        if (address.isAnyLocalAddress() || (address.isLoopbackAddress() && !master.isLoopbackAddress()))
            throw new IllegalArgumentException("WorkerAddress must be a reachable laptop IP, not 0.0.0.0 or remote-loopback");
        WorkerRuntime runtime = new WorkerRuntime(runtimeParallelism);
        List<WorkerNode> workers = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                String id = String.format("%s-%s%02d", machineId, prefix, workerOffset + i + 1);
                WorkerConfig cfg = new WorkerConfig(id, host, startPort + i, masterHost, masterPort);
                cfg.setCapacity(capacity); cfg.setBindHost(bindHost); cfg.setMachineId(machineId);
                cfg.setMachineName(machineName); cfg.setHostParallelism(hostParallelism);
                cfg.setProcessGroupId(processGroupId); cfg.setGroupParallelism(groupParallelism); cfg.setSupervised(supervised);
                cfg.setStartPaused(startPaused);
                WorkerNode node = new WorkerNode(cfg, runtime);
                try { node.start(); workers.add(node); }
                catch (IOException e) { System.err.printf("[WorkerCluster] BIND FAILED %s / %s:%d: %s%n", id, bindHost, startPort + i, e.getMessage()); }
            }
            if (workers.isEmpty()) throw new IOException("No Worker ports could be opened");
            if (requireAll && workers.size() != count)
                throw new IOException("Only " + workers.size() + "/" + count + " Worker ports opened; restarting this process group.");
            Cluster cluster = new Cluster(workers, runtime);
            System.out.printf("[WorkerCluster] laptop=%s (%s) | opened=%d/%d | ports=%d-%d | compute limit=%d | Master=%s:%d%n",
                machineId, machineName, workers.size(), count, startPort, startPort + count - 1, hostParallelism, masterHost, masterPort);
            System.out.println("[WorkerCluster] Waiting for REGISTER_ACK; opened ports are not confirmed registrations.");
            AtomicInteger previous = new AtomicInteger(-1);
            runtime.timerForStatus(() -> {
                int connected = cluster.registeredCount();
                if (previous.getAndSet(connected) != connected)
                    System.out.printf("[WorkerCluster] laptop=%s | REGISTERED %d/%d | opened %d/%d%n", machineId, connected, count, workers.size(), count);
            });
            return cluster;
        } catch (IOException | RuntimeException e) {
            workers.forEach(WorkerNode::stop); runtime.close(); throw e;
        }
    }

    public static List<WorkerNode> startCluster(int count, int startPort, String host, String masterHost,
                                               int masterPort, int capacity, String prefix) {
        WorkerConfig defaults = new WorkerConfig();
        return startCluster(count, startPort, host, masterHost, masterPort, capacity, prefix,
            defaults.getMachineId(), defaults.getMachineName(), "0.0.0.0", defaults.getHostParallelism());
    }

    public static List<WorkerNode> startCluster(int count, int startPort, String host, String masterHost,
                                               int masterPort, int capacity, String prefix, String machineId,
                                               String machineName, String bindHost, int parallelism) {
        return startCluster(count, startPort, host, masterHost, masterPort, capacity, prefix, machineId, machineName,
            bindHost, parallelism, parallelism, 0, machineId + "-group-01", parallelism);
    }

    public static List<WorkerNode> startCluster(int count, int startPort, String host, String masterHost,
                                               int masterPort, int capacity, String prefix, String machineId,
                                               String machineName, String bindHost, int hostParallelism,
                                               int runtimeParallelism, int workerOffset, String processGroupId,
                                               int groupParallelism) {
        try {
            Cluster cluster = launch(count, startPort, host, masterHost, masterPort, capacity, prefix, machineId, machineName,
                bindHost, hostParallelism, runtimeParallelism, workerOffset, processGroupId, groupParallelism);
            Runtime.getRuntime().addShutdownHook(new Thread(cluster::close));
            return cluster.getWorkers();
        } catch (IOException e) { throw new IllegalStateException("Cannot launch laptop Workers: " + e.getMessage(), e); }
    }
}
