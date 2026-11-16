package com.pbl4.app;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Restarts one child JVM after an unexpected non-zero exit. */
public final class ProcessSupervisor {
    private ProcessSupervisor() {}

    public static void main(String[] args) throws Exception {
        int restartLimit = 5, maxBackoffSeconds = 30, separator = -1;
        Path ownershipFile = null;
        for (int i = 0; i < args.length; i++) {
            if ("--".equals(args[i])) { separator = i; break; }
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing supervisor option value");
            if ("--restartLimit".equals(args[i])) restartLimit = Integer.parseInt(args[++i]);
            else if ("--maxBackoffSeconds".equals(args[i])) maxBackoffSeconds = Integer.parseInt(args[++i]);
            else if ("--ownershipFile".equals(args[i])) ownershipFile = Path.of(args[++i]);
            else throw new IllegalArgumentException("Unknown supervisor option " + args[i]);
        }
        if (separator < 0 || separator + 1 >= args.length || restartLimit < 0 || maxBackoffSeconds < 1)
            throw new IllegalArgumentException("Usage: ProcessSupervisor [--restartLimit N] [--maxBackoffSeconds N] -- <main-class> [args]");
        List<String> childArgs = List.of(Arrays.copyOfRange(args, separator + 1, args.length));
        SupervisorLease lease = ownershipFile == null ? null : SupervisorLease.acquire(ownershipFile);
        if (lease != null) {
            childArgs = new ArrayList<>(childArgs);
            childArgs.add("--supervisorLock"); childArgs.add(ownershipFile.toAbsolutePath().normalize().toString());
            childArgs.add("--supervisorPid"); childArgs.add(Long.toString(lease.pid()));
            childArgs.add("--supervisorToken"); childArgs.add(lease.token());
        }
        final List<String> managedChildArgs = childArgs;
        AtomicBoolean stopping = new AtomicBoolean();
        Process[] child = new Process[1];
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopping.set(true);
            Process p = child[0];
            if (p != null && p.isAlive()) {
                p.destroy();
                try { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); p.destroyForcibly(); }
            }
        }, "supervisor-shutdown"));

        int failures = 0;
        while (!stopping.get() && (lease == null || lease.isValid())) {
            child[0] = start(managedChildArgs);
            int exitCode = child[0].waitFor();
            child[0] = null;
            if (stopping.get() || exitCode == 0) return;
            failures++;
            if (failures > restartLimit) {
                System.err.println("[Supervisor] Restart limit reached; last exit=" + exitCode);
                System.exit(exitCode);
            }
            long delay = Math.min(maxBackoffSeconds, 1L << Math.min(20, failures - 1));
            System.err.println("[Supervisor] Child exited " + exitCode + "; restarting in " + delay + "s (" + failures + "/" + restartLimit + ")");
            Thread.sleep(delay * 1000);
        }
    }

    private static Process start(List<String> childArgs) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(executable, "-cp", System.getProperty("java.class.path")));
        command.addAll(childArgs);
        return new ProcessBuilder(command).inheritIO().start();
    }
}
