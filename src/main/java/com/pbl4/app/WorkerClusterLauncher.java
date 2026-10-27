package com.pbl4.app;

import com.pbl4.worker.*;
import java.io.IOException;
import java.net.InetAddress;
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
        @Override public void close() { workers.forEach(WorkerNode::stop); runtime.close(); }
    }

    public static void main(String[] args) throws Exception {
        WorkerConfig defaults = new WorkerConfig();
        int count = 50, startPort = 5001, masterPort = 5000, capacity = 1, parallelism = defaults.getHostParallelism();
        String host = "127.0.0.1", masterHost = "127.0.0.1", prefix = "worker-", bindHost = "0.0.0.0";
        String machineId = defaults.getMachineId(), machineName = defaults.getMachineName();
        for (int i = 0; i < args.length; i++) {
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
                case "--idPrefix" -> prefix = value;
                case "--machineId" -> machineId = value;
                case "--machineName" -> machineName = value;
                default -> throw new IllegalArgumentException("Unknown option " + args[i - 1]);
            }
        }
        startCluster(count, startPort, host, masterHost, masterPort, capacity, prefix, machineId, machineName, bindHost, parallelism);
    }

    public static Cluster launch(int count, int startPort, String host, String masterHost, int masterPort,
                                 int capacity, String prefix, String machineId, String machineName,
                                 String bindHost, int parallelism) throws IOException {
        if (count < 1 || count > 1024 || startPort < 1 || (long) startPort + count - 1 > 65535 || masterPort < 1 || masterPort > 65535 || capacity < 1)
            throw new IllegalArgumentException("Invalid worker count, port range or capacity");
        WorkerConfig validation = new WorkerConfig();
        validation.setMachineId(machineId); validation.setMachineName(machineName); validation.setHostParallelism(parallelism);
        InetAddress address = InetAddress.getByName(host), master = InetAddress.getByName(masterHost);
        if (master.isAnyLocalAddress()) throw new IllegalArgumentException("MasterAddress must be the Master laptop IP, not 0.0.0.0");
        if (address.isAnyLocalAddress() || (address.isLoopbackAddress() && !master.isLoopbackAddress()))
            throw new IllegalArgumentException("WorkerAddress must be a reachable laptop IP, not 0.0.0.0 or remote-loopback");
        WorkerRuntime runtime = new WorkerRuntime(parallelism);
        List<WorkerNode> workers = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                String id = String.format("%s-%s%02d", machineId, prefix, i + 1);
                WorkerConfig cfg = new WorkerConfig(id, host, startPort + i, masterHost, masterPort);
                cfg.setCapacity(capacity); cfg.setBindHost(bindHost); cfg.setMachineId(machineId);
                cfg.setMachineName(machineName); cfg.setHostParallelism(parallelism);
                WorkerNode node = new WorkerNode(cfg, runtime);
                try { node.start(); workers.add(node); }
                catch (IOException e) { System.err.printf("[WorkerCluster] BIND FAILED %s / %s:%d: %s%n", id, bindHost, startPort + i, e.getMessage()); }
            }
            if (workers.isEmpty()) throw new IOException("No Worker ports could be opened");
            Cluster cluster = new Cluster(workers, runtime);
            System.out.printf("[WorkerCluster] laptop=%s (%s) | opened=%d/%d | ports=%d-%d | compute limit=%d | Master=%s:%d%n",
                machineId, machineName, workers.size(), count, startPort, startPort + count - 1, parallelism, masterHost, masterPort);
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
        try {
            Cluster cluster = launch(count, startPort, host, masterHost, masterPort, capacity, prefix, machineId, machineName, bindHost, parallelism);
            Runtime.getRuntime().addShutdownHook(new Thread(cluster::close));
            return cluster.getWorkers();
        } catch (IOException e) { throw new IllegalStateException("Cannot launch laptop Workers: " + e.getMessage(), e); }
    }
}
