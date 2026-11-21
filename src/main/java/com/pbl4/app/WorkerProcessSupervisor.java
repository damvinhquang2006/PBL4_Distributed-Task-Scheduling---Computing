package com.pbl4.app;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Supervises independent Worker JVM groups while preserving one laptop-wide CPU budget. */
public final class WorkerProcessSupervisor {
    private record Group(String id, int count, int firstPort, int workerOffset, int parallelism) {}
    private WorkerProcessSupervisor() {}

    public static void main(String[] args) throws Exception {
        boolean startPaused = false;
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if ("--startPaused".equals(args[i])) {
                startPaused = true;
                continue;
            }
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + args[i]);
            options.put(args[i], args[++i]);
        }
        int count = integer(options, "--count", 50), groups = integer(options, "--groups", 1);
        int startPort = integer(options, "--startPort", 5001), masterPort = integer(options, "--masterPort", 5000);
        int hostParallelism = integer(options, "--hostParallelism", Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        int capacity = integer(options, "--capacity", 1), restartLimit = integer(options, "--restartLimit", 5);
        int maxBackoffSeconds = integer(options, "--maxBackoffSeconds", 30);
        String host = required(options, "--host"), masterHost = required(options, "--masterHost");
        String machineId = required(options, "--machineId"), machineName = options.getOrDefault("--machineName", machineId);
        String bindHost = options.getOrDefault("--bindHost", "0.0.0.0");
        if (count < 1 || groups < 1 || groups > count || groups > hostParallelism || startPort + count - 1 > 65535
            || restartLimit < 0 || maxBackoffSeconds < 1) throw new IllegalArgumentException("Invalid worker group count, port range, or restart policy");
        Path ownershipFile = Path.of("data", "worker-supervisor", machineId + "-" + startPort + ".lock").toAbsolutePath().normalize();
        SupervisorLease lease = SupervisorLease.acquire(ownershipFile);

        List<Group> groupList = new ArrayList<>(); int workerOffset = 0, portOffset = 0;
        for (int i = 0; i < groups; i++) {
            int workers = count / groups + (i < count % groups ? 1 : 0);
            int budget = hostParallelism / groups + (i < hostParallelism % groups ? 1 : 0);
            groupList.add(new Group(machineId + "-group-" + String.format("%02d", i + 1),
                workers, startPort + portOffset, workerOffset, budget));
            workerOffset += workers; portOffset += workers;
        }

        AtomicBoolean stopping = new AtomicBoolean();
        Process[] children = new Process[groups]; int[] failures = new int[groups]; long[] restartAt = new long[groups];
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopping.set(true);
            for (Process child : children) if (child != null && child.isAlive()) child.destroy();
            for (Process child : children) if (child != null && child.isAlive()) try {
                if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); child.destroyForcibly(); }
        }, "worker-supervisor-shutdown"));

        for (int i = 0; i < groups; i++) children[i] = start(groupList.get(i), host, masterHost, masterPort,
            machineId, machineName, bindHost, hostParallelism, capacity, ownershipFile, lease, startPaused);
        while (!stopping.get() && lease.isValid()) {
            for (int i = 0; i < groups; i++) {
                Process child = children[i];
                if (child == null || child.isAlive()) continue;
                if (restartAt[i] == 0) {
                    int exit = child.exitValue(); failures[i]++;
                    if (failures[i] > restartLimit) {
                        System.err.printf("[WorkerSupervisor] %s restart limit reached (exit=%d)%n", groupList.get(i).id(), exit);
                        children[i] = null; continue;
                    }
                    long delay = Math.min(maxBackoffSeconds, 1L << Math.min(20, failures[i] - 1));
                    restartAt[i] = System.currentTimeMillis() + delay * 1000;
                    System.err.printf("[WorkerSupervisor] %s exited %d; restarting in %ds (%d/%d)%n",
                        groupList.get(i).id(), exit, delay, failures[i], restartLimit);
                } else if (System.currentTimeMillis() >= restartAt[i] && !stopping.get()) {
                    children[i] = start(groupList.get(i), host, masterHost, masterPort,
                        machineId, machineName, bindHost, hostParallelism, capacity, ownershipFile, lease, startPaused);
                    restartAt[i] = 0;
                }
            }
            if (!stopping.get()) Thread.sleep(250);
        }
    }

    private static Process start(Group group, String host, String masterHost, int masterPort, String machineId,
                                 String machineName, String bindHost, int hostParallelism, int capacity,
                                 Path ownershipFile, SupervisorLease lease, boolean startPaused) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"),
            WorkerClusterLauncher.class.getName()));
        add(command, "--count", group.count()); add(command, "--startPort", group.firstPort());
        add(command, "--host", host); add(command, "--bindHost", bindHost);
        add(command, "--masterHost", masterHost); add(command, "--masterPort", masterPort);
        add(command, "--capacity", capacity); add(command, "--machineId", machineId);
        add(command, "--machineName", machineName); add(command, "--hostParallelism", hostParallelism);
        add(command, "--runtimeParallelism", group.parallelism()); add(command, "--groupParallelism", group.parallelism());
        add(command, "--workerOffset", group.workerOffset()); add(command, "--processGroupId", group.id());
        command.add("--requireAll");
        if (startPaused) command.add("--startPaused");
        add(command, "--supervisorLock", ownershipFile);
        add(command, "--supervisorPid", lease.pid()); add(command, "--supervisorToken", lease.token());
        System.out.printf("[WorkerSupervisor] START %s | workers=%d | ports=%d-%d | compute=%d/%d%n",
            group.id(), group.count(), group.firstPort(), group.firstPort() + group.count() - 1, group.parallelism(), hostParallelism);
        return new ProcessBuilder(command).inheritIO().start();
    }
    private static void add(List<String> args, String key, Object value) { args.add(key); args.add(String.valueOf(value)); }
    private static int integer(Map<String, String> opts, String key, int fallback) {
        return Integer.parseInt(opts.getOrDefault(key, String.valueOf(fallback)));
    }
    private static String required(Map<String, String> opts, String key) {
        String value = opts.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key); return value;
    }
}
