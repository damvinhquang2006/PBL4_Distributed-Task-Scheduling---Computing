package com.pbl4.master;

import com.pbl4.app.SupervisorLease;
import com.pbl4.app.WorkerClusterLauncher;
import com.pbl4.core.model.WorkerInfo;
import com.pbl4.core.model.WorkerStatus;
import com.pbl4.worker.WorkerConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages local child-JVM WorkerClusterLauncher batches on the Master host for interactive demos.
 */
public final class LocalWorkerManager implements AutoCloseable {
    public static final int MAX_MANAGED_WORKERS = 64;
    public static final int MAX_BATCH_SIZE = 32;
    public static final int DEFAULT_START_PORT = 5201;
    private static final long REGISTRATION_TIMEOUT_MS = 8_000L;
    private static final long POLL_INTERVAL_MS = 50L;
    private static final long STOP_GRACEFUL_WAIT_MS = 2_500L;
    private static final long STOP_FORCE_WAIT_MS = 1_000L;
    private static final int MAX_RETAINED_STOPPED_BATCHES = 20;

    public static final class MasterNotRunningException extends IllegalStateException {
        public MasterNotRunningException(String message) { super(message); }
    }

    public static final class BatchLaunchException extends IllegalStateException {
        public BatchLaunchException(String message) { super(message); }
        public BatchLaunchException(String message, Throwable cause) { super(message, cause); }
    }

    public record StartRequest(
            int count,
            Integer startPort,
            String machineId,
            String machineName,
            Integer capacity,
            String displayNamePrefix,
            boolean startPaused
    ) {}

    private static final class ManagedBatch {
        final String batchId;
        final String machineId;
        final String machineName;
        final int startPort;
        final int count;
        final List<String> workerIds;
        final long startedAt;
        final boolean startPaused;
        final SupervisorLease lease;
        final Process process;
        final Path logPath;
        final Path lockPath;

        ManagedBatch(String batchId, String machineId, String machineName, int startPort, int count,
                     List<String> workerIds, long startedAt, boolean startPaused,
                     SupervisorLease lease, Process process, Path logPath, Path lockPath) {
            this.batchId = batchId;
            this.machineId = machineId;
            this.machineName = machineName;
            this.startPort = startPort;
            this.count = count;
            this.workerIds = List.copyOf(workerIds);
            this.startedAt = startedAt;
            this.startPaused = startPaused;
            this.lease = lease;
            this.process = process;
            this.logPath = logPath;
            this.lockPath = lockPath;
        }

        boolean isAlive() {
            return process != null && process.isAlive();
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("batchId", batchId);
            map.put("machineId", machineId);
            map.put("machineName", machineName);
            map.put("startPort", startPort);
            map.put("count", count);
            map.put("workerIds", workerIds);
            map.put("alive", isAlive());
            map.put("startedAt", startedAt);
            map.put("startPaused", startPaused);
            return map;
        }
    }

    private final MasterServer masterServer;
    private final Path managedDir;
    private final Map<String, ManagedBatch> batches = new LinkedHashMap<>();
    private final AtomicInteger batchSequence = new AtomicInteger();

    public LocalWorkerManager(MasterServer masterServer, Path managedDir) {
        this.masterServer = Objects.requireNonNull(masterServer);
        this.managedDir = managedDir.toAbsolutePath().normalize();
    }

    public synchronized Map<String, Object> getStatusSnapshot() {
        List<Map<String, Object>> batchList = batches.values().stream()
                .map(ManagedBatch::toMap)
                .toList();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("batches", batchList);
        resp.put("maxManagedWorkers", MAX_MANAGED_WORKERS);
        resp.put("maxBatchSize", MAX_BATCH_SIZE);
        resp.put("activeManagedCount", activeManagedCountInternal());
        resp.put("defaultMachineId", resolveDefaultMachineId());
        resp.put("running", masterServer.isRunning());
        return resp;
    }

    public synchronized Map<String, Object> startBatch(StartRequest req) {
        if (!masterServer.isTcpListening()) {
            throw new MasterNotRunningException("Master TCP server is not running");
        }
        if (req.count() < 1 || req.count() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("count must be between 1 and " + MAX_BATCH_SIZE);
        }
        int activeManaged = activeManagedCountInternal();
        if (activeManaged + req.count() > MAX_MANAGED_WORKERS) {
            throw new IllegalArgumentException("Cannot exceed maxManagedWorkers (" + MAX_MANAGED_WORKERS
                    + "); active=" + activeManaged + ", requested=" + req.count());
        }
        int capacity = req.capacity() == null ? 1 : req.capacity();
        if (capacity < 1 || capacity > 4) {
            throw new IllegalArgumentException("capacity must be between 1 and 4");
        }
        int preferredStartPort = req.startPort() == null ? DEFAULT_START_PORT : req.startPort();
        if (preferredStartPort < 1 || preferredStartPort > 65535) {
            throw new IllegalArgumentException("startPort must be between 1 and 65535");
        }

        String machineId = (req.machineId() == null || req.machineId().isBlank())
                ? resolveDefaultMachineId()
                : req.machineId().strip();
        if (!machineId.matches("[a-zA-Z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("machineId must contain 1-64 characters [a-zA-Z0-9._-]");
        }

        WorkerInfo existingMachineWorker = masterServer.getRegistry().getAllWorkers().stream()
                .filter(w -> w.getStatus() != WorkerStatus.OFFLINE && machineId.equals(w.machineId))
                .findFirst()
                .orElse(null);

        int hostParallelism = existingMachineWorker != null
                ? existingMachineWorker.hostParallelism
                : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        String defaultMachineName = existingMachineWorker != null
                ? existingMachineWorker.machineName
                : (machineId.equals(new WorkerConfig().getMachineId()) ? new WorkerConfig().getMachineName() : machineId);
        String machineName = (req.machineName() == null || req.machineName().isBlank())
                ? defaultMachineName
                : req.machineName().strip();

        String normalizedPrefix = normalizeAliasPrefix(req.displayNamePrefix());
        int workerOffset = nextWorkerOffset(machineId);
        List<String> expectedWorkerIds = new ArrayList<>(req.count());
        for (int i = 0; i < req.count(); i++) {
            expectedWorkerIds.add(String.format("%s-worker-%02d", machineId, workerOffset + i + 1));
        }

        int allocatedStartPort = findFreePortRange(preferredStartPort, req.count());
        String batchId = "batch-" + String.format("%03d", batchSequence.incrementAndGet());

        try {
            Files.createDirectories(managedDir);
        } catch (IOException e) {
            throw new BatchLaunchException("Cannot create managed-workers directory: " + e.getMessage(), e);
        }

        Path lockPath = managedDir.resolve(batchId + ".lock");
        Path logPath = managedDir.resolve(batchId + ".log");
        SupervisorLease lease;
        try {
            lease = SupervisorLease.acquire(lockPath);
        } catch (IOException e) {
            throw new BatchLaunchException("Cannot acquire supervisor lease for " + batchId + ": " + e.getMessage(), e);
        }

        Process process = null;
        try {
            String masterConnectHost = resolveMasterConnectHost();
            List<String> command = buildLaunchCommand(
                    req.count(), allocatedStartPort, masterConnectHost, masterServer.getActualPort(),
                    capacity, machineId, machineName, hostParallelism, workerOffset, batchId,
                    lockPath, lease, req.startPaused());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
            process = pb.start();

            waitForRegistration(process, expectedWorkerIds, logPath);

            if (normalizedPrefix != null) {
                for (int i = 0; i < expectedWorkerIds.size(); i++) {
                    String wid = expectedWorkerIds.get(i);
                    String alias = String.format("%s-%02d", normalizedPrefix, i + 1);
                    masterServer.renameWorker(wid, alias);
                }
            }

            ManagedBatch batch = new ManagedBatch(
                    batchId, machineId, machineName, allocatedStartPort, req.count(),
                    expectedWorkerIds, System.currentTimeMillis(), req.startPaused(),
                    lease, process, logPath, lockPath);
            batches.put(batchId, batch);
            pruneStoppedBatches();

            Set<String> idSet = new HashSet<>(expectedWorkerIds);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> allWorkers = (List<Map<String, Object>>) masterServer.getClusterSnapshot().get("workers");
            List<Map<String, Object>> batchWorkers = allWorkers.stream()
                    .filter(row -> idSet.contains(row.get("workerId")))
                    .toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("batch", batch.toMap());
            result.put("workers", batchWorkers);
            return result;
        } catch (RuntimeException e) {
            closeQuietly(lease);
            destroyProcessQuietly(process);
            masterServer.getRegistry().markWorkersOffline(expectedWorkerIds);
            throw e;
        } catch (Exception e) {
            closeQuietly(lease);
            destroyProcessQuietly(process);
            masterServer.getRegistry().markWorkersOffline(expectedWorkerIds);
            throw new BatchLaunchException("Failed to start local workers: " + e.getMessage(), e);
        }
    }

    public synchronized Map<String, Object> stopBatch(String batchId, boolean all) {
        if (!all && (batchId == null || batchId.isBlank())) {
            throw new IllegalArgumentException("batchId or all=true is required");
        }
        List<ManagedBatch> targets = new ArrayList<>();
        if (all) {
            for (ManagedBatch batch : batches.values()) {
                if (batch.isAlive() || hasOnlineWorker(batch.workerIds)) {
                    targets.add(batch);
                }
            }
        } else {
            ManagedBatch batch = batches.get(batchId.strip());
            if (batch == null) {
                throw new NoSuchElementException("Batch not found: " + batchId);
            }
            targets.add(batch);
        }

        List<String> stoppedBatchIds = new ArrayList<>();
        List<String> offlineWorkerIds = new ArrayList<>();
        for (ManagedBatch batch : targets) {
            closeQuietly(batch.lease);
            destroyProcessGracefully(batch.process);
            offlineWorkerIds.addAll(masterServer.getRegistry().markWorkersOffline(batch.workerIds));
            stoppedBatchIds.add(batch.batchId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("stoppedBatchIds", stoppedBatchIds);
        result.put("offlineWorkerIds", offlineWorkerIds);
        return result;
    }

    @Override
    public synchronized void close() {
        for (ManagedBatch batch : batches.values()) {
            closeQuietly(batch.lease);
            destroyProcessGracefully(batch.process);
            masterServer.getRegistry().markWorkersOffline(batch.workerIds);
        }
    }

    private int activeManagedCountInternal() {
        return batches.values().stream()
                .filter(ManagedBatch::isAlive)
                .mapToInt(b -> b.count)
                .sum();
    }

    private boolean hasOnlineWorker(List<String> workerIds) {
        for (String wid : workerIds) {
            WorkerInfo w = masterServer.getRegistry().getWorker(wid);
            if (w != null && w.getStatus() != WorkerStatus.OFFLINE) {
                return true;
            }
        }
        return false;
    }

    private void pruneStoppedBatches() {
        List<String> stoppedIds = batches.values().stream()
                .filter(b -> !b.isAlive())
                .map(b -> b.batchId)
                .toList();
        int excess = stoppedIds.size() - MAX_RETAINED_STOPPED_BATCHES;
        for (int i = 0; i < excess; i++) {
            batches.remove(stoppedIds.get(i));
        }
    }

    private String resolveDefaultMachineId() {
        Set<String> localIps = localInterfaceAddresses();
        return masterServer.getRegistry().getAllWorkers().stream()
                .filter(w -> w.getStatus() != WorkerStatus.OFFLINE && isLocalWorkerHost(w.getHost(), localIps))
                .sorted(Comparator.comparing(WorkerInfo::getWorkerId))
                .map(w -> w.machineId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElseGet(() -> {
                    String fallback = new WorkerConfig().getMachineId();
                    return (fallback != null && !fallback.isBlank()) ? fallback : "local-ui";
                });
    }

    private static Set<String> localInterfaceAddresses() {
        Set<String> ips = new HashSet<>(Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost"));
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    ips.add(addrs.nextElement().getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return ips;
    }

    private static boolean isLocalWorkerHost(String host, Set<String> localIps) {
        if (host == null || host.isBlank()) return false;
        return localIps.contains(host);
    }

    private String resolveMasterConnectHost() {
        String bindHost = masterServer.getBindHost();
        if (bindHost == null || bindHost.isBlank() || "0.0.0.0".equals(bindHost) || "::".equals(bindHost)) {
            return "127.0.0.1";
        }
        return bindHost;
    }

    private int nextWorkerOffset(String machineId) {
        Pattern pattern = Pattern.compile(Pattern.quote(machineId + "-worker-") + "(\\d+)$");
        int maxIndex = 0;
        for (WorkerInfo w : masterServer.getRegistry().getAllWorkers()) {
            Matcher m = pattern.matcher(w.getWorkerId());
            if (m.matches()) {
                try {
                    maxIndex = Math.max(maxIndex, Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) { }
            }
        }
        for (ManagedBatch b : batches.values()) {
            for (String wid : b.workerIds) {
                Matcher m = pattern.matcher(wid);
                if (m.matches()) {
                    try {
                        maxIndex = Math.max(maxIndex, Integer.parseInt(m.group(1)));
                    } catch (NumberFormatException ignored) { }
                }
            }
        }
        return maxIndex;
    }

    private int findFreePortRange(int preferredStartPort, int count) {
        Set<Integer> reservedPorts = new HashSet<>();
        for (WorkerInfo w : masterServer.getRegistry().getAllWorkers()) {
            if (w.getStatus() != WorkerStatus.OFFLINE) {
                reservedPorts.add(w.getPort());
            }
        }
        for (ManagedBatch batch : batches.values()) {
            if (batch.isAlive()) {
                for (int i = 0; i < batch.count; i++) {
                    reservedPorts.add(batch.startPort + i);
                }
            }
        }

        int candidate = preferredStartPort;
        while (candidate + count - 1 <= 65535) {
            int busyOffset = -1;
            for (int i = 0; i < count; i++) {
                int port = candidate + i;
                if (reservedPorts.contains(port) || !isPortFree(port)) {
                    busyOffset = i;
                    break;
                }
            }
            if (busyOffset < 0) {
                return candidate;
            }
            candidate += busyOffset + 1;
        }
        throw new BatchLaunchException("No free consecutive port range of size " + count + " from " + preferredStartPort);
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket wildcard = new ServerSocket()) {
            wildcard.setReuseAddress(false);
            wildcard.bind(new InetSocketAddress("0.0.0.0", port));
        } catch (IOException e) {
            return false;
        }
        try (ServerSocket loopback = new ServerSocket()) {
            loopback.setReuseAddress(false);
            loopback.bind(new InetSocketAddress("127.0.0.1", port));
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    private static String normalizeAliasPrefix(String rawPrefix) {
        if (rawPrefix == null || rawPrefix.isBlank()) return null;
        String stripped = rawPrefix.strip();
        if (stripped.endsWith("-") && stripped.length() > 1) {
            stripped = stripped.substring(0, stripped.length() - 1).strip();
        }
        if (stripped.isEmpty() || stripped.chars().anyMatch(Character::isISOControl) || stripped.length() + 3 > 64) {
            throw new IllegalArgumentException("displayNamePrefix must contain 1-61 visible characters");
        }
        return stripped;
    }

    private List<String> buildLaunchCommand(int count, int startPort, String masterConnectHost, int masterPort,
                                            int capacity, String machineId, String machineName,
                                            int hostParallelism, int workerOffset, String processGroupId,
                                            Path lockPath, SupervisorLease lease, boolean startPaused) {
        String javaExe = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(WorkerClusterLauncher.class.getName());
        cmd.add("--count"); cmd.add(String.valueOf(count));
        cmd.add("--startPort"); cmd.add(String.valueOf(startPort));
        cmd.add("--host"); cmd.add(masterConnectHost);
        cmd.add("--bindHost"); cmd.add("0.0.0.0");
        cmd.add("--masterHost"); cmd.add(masterConnectHost);
        cmd.add("--masterPort"); cmd.add(String.valueOf(masterPort));
        cmd.add("--capacity"); cmd.add(String.valueOf(capacity));
        cmd.add("--machineId"); cmd.add(machineId);
        cmd.add("--machineName"); cmd.add(machineName);
        cmd.add("--hostParallelism"); cmd.add(String.valueOf(hostParallelism));
        cmd.add("--runtimeParallelism"); cmd.add(String.valueOf(hostParallelism));
        cmd.add("--groupParallelism"); cmd.add(String.valueOf(hostParallelism));
        cmd.add("--workerOffset"); cmd.add(String.valueOf(workerOffset));
        cmd.add("--processGroupId"); cmd.add(processGroupId);
        cmd.add("--requireAll");
        cmd.add("--supervisorLock"); cmd.add(lockPath.toString());
        cmd.add("--supervisorPid"); cmd.add(String.valueOf(lease.pid()));
        cmd.add("--supervisorToken"); cmd.add(lease.token());
        if (startPaused) {
            cmd.add("--startPaused");
        }
        return cmd;
    }

    private void waitForRegistration(Process process, List<String> expectedWorkerIds, Path logPath) throws InterruptedException {
        long deadline = System.currentTimeMillis() + REGISTRATION_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                int exitCode = process.exitValue();
                String tail = readLogTail(logPath, 20);
                throw new BatchLaunchException("Child worker JVM exited prematurely with code " + exitCode
                        + (tail.isBlank() ? "" : " | log: " + tail));
            }
            boolean allRegistered = true;
            for (String wid : expectedWorkerIds) {
                WorkerInfo info = masterServer.getRegistry().getWorker(wid);
                if (info == null || info.getStatus() == WorkerStatus.OFFLINE) {
                    allRegistered = false;
                    break;
                }
            }
            if (allRegistered) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        String tail = readLogTail(logPath, 20);
        throw new BatchLaunchException("Timed out waiting for managed workers to register within "
                + REGISTRATION_TIMEOUT_MS + " ms" + (tail.isBlank() ? "" : " | log: " + tail));
    }

    private static String readLogTail(Path logPath, int maxLines) {
        try {
            if (!Files.exists(logPath)) return "";
            List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - maxLines);
            return String.join("\n", lines.subList(from, lines.size())).strip();
        } catch (IOException e) {
            return "";
        }
    }

    private static void closeQuietly(SupervisorLease lease) {
        if (lease != null) {
            try { lease.close(); } catch (IOException ignored) { }
        }
    }

    private static void destroyProcessQuietly(Process process) {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private static void destroyProcessGracefully(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(STOP_GRACEFUL_WAIT_MS, TimeUnit.MILLISECONDS) && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(STOP_FORCE_WAIT_MS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
