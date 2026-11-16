package com.pbl4;

import com.pbl4.app.WorkerClusterLauncher;
import com.pbl4.core.model.*;
import com.pbl4.core.network.*;
import com.pbl4.master.MasterServer;
import com.pbl4.master.http.MasterHttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import com.pbl4.worker.*;
import static org.junit.jupiter.api.Assertions.*;

class LaptopClusterTest {
    private int sharedPortRange(int count) throws Exception {
        for (int base = 23000; base + count < 60000; base += count) {
            List<ServerSocket> sockets = new ArrayList<>();
            try {
                for (String host : List.of("127.0.0.1", "127.0.0.2")) for (int i = 0; i < count; i++) {
                    ServerSocket socket = new ServerSocket(); sockets.add(socket);
                    socket.bind(new InetSocketAddress(host, base + i));
                }
                return base;
            } catch (java.io.IOException ignored) { }
            finally { for (ServerSocket socket : sockets) socket.close(); }
        }
        throw new IllegalStateException("No free test port range");
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> json(String body) throws Exception { return JsonUtils.getMapper().readValue(body, Map.class); }

    @SuppressWarnings("unchecked")
    @Test void fiftyPerLaptopRegistersAndComputesWithoutCreatingWorkers() throws Exception {
        int mp = LanLifecycleTest.port(), hp = LanLifecycleTest.port(), basePort = sharedPortRange(50);
        MasterServer master = new MasterServer(mp); master.setJobTimeoutMs(15_000);
        MasterHttpServer http = new MasterHttpServer(master, hp);
        WorkerClusterLauncher.Cluster a = null, b = null;
        try (HttpClient client = HttpClient.newHttpClient()) {
            master.start(); http.start();
            a = WorkerClusterLauncher.launch(50, basePort, "127.0.0.1", "127.0.0.1", mp, 1, "worker-", "laptop-a", "Laptop A", "127.0.0.1", 2);
            b = WorkerClusterLauncher.launch(50, basePort, "127.0.0.2", "127.0.0.1", mp, 1, "worker-", "laptop-b", "Laptop B", "127.0.0.2", 3);
            WorkerClusterLauncher.Cluster clusterA = a, clusterB = b;
            LanLifecycleTest.await(() -> clusterA.registeredCount() == 50 && clusterB.registeredCount() == 50, 15_000);
            String api = "http://127.0.0.1:" + hp + "/api";
            for (int reload = 0; reload < 2; reload++) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(api + "/cluster")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                Map<String,Object> snapshot = json(response.body());
                assertEquals(100, ((Number) snapshot.get("connectedWorkers")).intValue());
                assertEquals(5, ((Number) snapshot.get("effectiveParallelism")).intValue());
                List<Map<String,Object>> machines = (List<Map<String,Object>>) snapshot.get("machines");
                assertEquals(2, machines.size());
                for (Map<String,Object> machine : machines) assertEquals(50, ((Number) machine.get("connectedWorkers")).intValue());
                List<Map<String,Object>> workers = (List<Map<String,Object>>) snapshot.get("workers");
                assertEquals(100, workers.stream().map(w -> w.get("workerId")).distinct().count());
                assertTrue(workers.stream().allMatch(w -> w.get("workerId").toString().startsWith(w.get("machineId") + "-worker-")));
            }
            String body = JsonUtils.getMapper().writeValueAsString(Map.of("operation", "x", "rowsA", 100, "colsA", 100,
                "rowsB", 100, "colsB", 100, "genA", Map.of("generator", "constant-v1", "val", 2), "genB", Map.of("generator", "identity-v1")));
            HttpResponse<String> accepted = client.send(HttpRequest.newBuilder(URI.create(api + "/compute/async"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, accepted.statusCode(), accepted.body());
            Map<String,Object> ack = json(accepted.body());
            assertEquals(10, ((Number) ack.get("totalTasks")).intValue()); // 2 x (2 + 3), not 2 x 100 Workers.
            String jobId = (String) ack.get("jobId");
            LanLifecycleTest.await(() -> {
                assertTrue(clusterA.runningTasks() <= 2); assertTrue(clusterB.runningTasks() <= 3);
                assertTrue(master.getScheduler().machineActive("laptop-a") <= 2);
                assertTrue(master.getScheduler().machineActive("laptop-b") <= 3);
                return !"RUNNING".equals(master.getJobProgress(jobId).get("status"));
            }, 16_000);
            Map<String,Object> progress = master.getJobProgress(jobId);
            assertEquals("COMPLETED", progress.get("status"), progress.toString());
            Map<String,Object> result = (Map<String,Object>) progress.get("resultSummary");
            assertEquals(List.of("laptop-a", "laptop-b"), result.get("machineIds"));
            double[][] matrix = JsonUtils.getMapper().convertValue(result.get("result"), double[][].class);
            assertEquals(100, matrix.length);
            for (double[] row : matrix) { assertEquals(100, row.length); for (double value : row) assertEquals(2., value); }
            assertEquals(100, master.getRegistry().getAllWorkers().size());
            HttpResponse<String> removed = client.send(HttpRequest.newBuilder(URI.create(api + "/cluster/scale50"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(410, removed.statusCode());
            assertEquals(100, master.getRegistry().getAllWorkers().size());
            b.close(); b = null;
            master.getRegistry().getAllWorkers().stream().filter(w -> w.machineId.equals("laptop-b"))
                .forEach(w -> w.setLastHeartbeatTime(System.currentTimeMillis() - 10_000));
            master.getRegistry().checkHealth();
            Map<String,Object> disconnected = json(client.send(HttpRequest.newBuilder(URI.create(api + "/cluster")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(50, ((Number) disconnected.get("connectedWorkers")).intValue());
            assertEquals(50, ((Number) disconnected.get("offlineWorkers")).intValue());
        } finally { if (a != null) a.close(); if (b != null) b.close(); http.stop(); master.stop(); }
    }

    @Test void pendingClusterRegistersAfterMasterStartsAndReRegistersAfterRestart() throws Exception {
        int mp = LanLifecycleTest.port(), base = sharedPortRange(3);
        MasterServer master = new MasterServer(mp), replacement = null;
        try (WorkerClusterLauncher.Cluster cluster = WorkerClusterLauncher.launch(3, base, "127.0.0.2", "127.0.0.1", mp,
                1, "worker-", "late-b", "Late laptop B", "127.0.0.2", 1)) {
            assertEquals(0, cluster.registeredCount());
            master.start(); LanLifecycleTest.await(() -> cluster.registeredCount() == 3, 10_000);
            String oldSession = master.getClusterSnapshot().get("masterSession").toString();
            master.stop(); replacement = new MasterServer(mp); replacement.start(); MasterServer current = replacement;
            LanLifecycleTest.await(() -> current.getRegistry().getAvailableWorkers().size() == 3 && cluster.registeredCount() == 3, 12_000);
            assertNotEquals(oldSession, current.getClusterSnapshot().get("masterSession"));
            assertEquals(9., current.runArithmeticStarter("1+2", "3*2", "+"));
        } finally { master.stop(); if (replacement != null) replacement.stop(); }
    }

    @Test void versionTwoIsRejectedBeforeRegistration() throws Exception {
        int mp = LanLifecycleTest.port(); MasterServer master = new MasterServer(mp);
        try {
            master.start(); Message old = new Message(MessageType.REGISTER_WORKER, "old"); old.protocolVersion = 2;
            Message response = SocketMessenger.sendAndReceive("127.0.0.1", mp, old, 2000);
            assertEquals(MessageType.REGISTER_REJECTED, response.getType());
            assertTrue(response.getContent().contains("v" + Message.CURRENT_VERSION) || response.getContent().contains("v4")); assertTrue(response.getContent().contains("JAR"));
            assertTrue(master.getRegistry().getAllWorkers().isEmpty());
        } finally { master.stop(); }
    }

    @SuppressWarnings("unchecked")
    @Test void lateLaptopTakesPendingWorkWhileExistingLaptopIsBusy() throws Exception {
        int mp = LanLifecycleTest.port(), base = sharedPortRange(3);
        MasterServer master = new MasterServer(mp); master.setJobTimeoutMs(15_000);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        WorkerConfig cfg = new WorkerConfig("laptop-a-worker-01", "127.0.0.1", LanLifecycleTest.port(), "127.0.0.1", mp);
        cfg.setMachineId("laptop-a"); cfg.setHostParallelism(1);
        WorkerNode a = new WorkerNode(cfg) {
            @Override protected TaskResult executeTask(Task task) {
                entered.countDown();
                try { release.await(8, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return super.executeTask(task);
            }
        };
        WorkerClusterLauncher.Cluster b = null;
        try {
            master.start(); a.start(); LanLifecycleTest.await(a::isRegistered, 4000);
            MasterServer.AsyncJobRecord rec = master.submitAsyncMatrixJob("x",100,100,100,100,
                Map.of("generator","constant-v1","val",2), Map.of("generator","identity-v1"), null, 8);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            b = WorkerClusterLauncher.launch(3,base,"127.0.0.2","127.0.0.1",mp,1,"worker-","laptop-b","Laptop B","127.0.0.2",2);
            LanLifecycleTest.await(() -> rec.workerCompletedTasks.keySet().stream().anyMatch(id -> id.startsWith("laptop-b-")), 5000);
            assertEquals(JobStatus.RUNNING, rec.status);
            release.countDown(); LanLifecycleTest.await(() -> rec.status != JobStatus.RUNNING, 4000);
            assertEquals(JobStatus.COMPLETED, rec.status, rec.error);
            assertEquals(List.of("laptop-a","laptop-b"), rec.resultSummary.get("machineIds"));
        } finally { release.countDown(); a.stop(); if (b != null) b.close(); master.stop(); }
    }
}
