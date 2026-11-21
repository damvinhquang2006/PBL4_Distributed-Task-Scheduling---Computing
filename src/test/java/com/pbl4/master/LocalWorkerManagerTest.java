package com.pbl4.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.pbl4.app.SupervisorLease;
import com.pbl4.core.model.WorkerInfo;
import com.pbl4.core.model.WorkerStatus;
import com.pbl4.core.network.JsonUtils;
import com.pbl4.master.http.MasterHttpServer;
import com.pbl4.worker.WorkerConfig;
import com.pbl4.worker.WorkerNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class LocalWorkerManagerTest {
    @TempDir Path tempDir;

    @Test
    void returns409BeforeMasterStartAndValidatesInputs() throws Exception {
        int httpPort = freePort();
        Path dataDir = tempDir.resolve("master-not-started");
        MasterServer master = new MasterServer(0, "127.0.0.1", dataDir);
        MasterHttpServer http = new MasterHttpServer(master, httpPort);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        String api = "http://127.0.0.1:" + httpPort + "/api";

        try {
            http.start();

            HttpResponse<String> statusBefore = request(client, api + "/local-workers", "GET", null);
            assertEquals(200, statusBefore.statusCode(), statusBefore.body());
            JsonNode beforeJson = JsonUtils.getMapper().readTree(statusBefore.body());
            assertFalse(beforeJson.path("running").asBoolean());
            assertEquals(64, beforeJson.path("maxManagedWorkers").asInt());
            assertEquals(32, beforeJson.path("maxBatchSize").asInt());
            assertEquals(0, beforeJson.path("activeManagedCount").asInt());

            HttpResponse<String> notRunning = request(client, api + "/local-workers/start", "POST", Map.of("count", 1));
            assertEquals(409, notRunning.statusCode(), notRunning.body());

            master.start();

            assertEquals(400, request(client, api + "/local-workers/start", "POST", Map.of("count", 0)).statusCode());
            assertEquals(400, request(client, api + "/local-workers/start", "POST", Map.of("count", 33)).statusCode());
            assertEquals(400, request(client, api + "/local-workers/start", "POST", Map.of("count", 1, "capacity", 5)).statusCode());
            assertEquals(400, request(client, api + "/local-workers/stop", "POST", Map.of()).statusCode());
            assertEquals(404, request(client, api + "/local-workers/stop", "POST", Map.of("batchId", "unknown")).statusCode());
        } finally {
            http.stop();
            master.stop();
        }
    }

    @Test
    void startsChildJvmSkipsBusyPortsInheritsHostParallelismStopsImmediatelyAndCleansUpOnMasterStop() throws Exception {
        int httpPort = freePort();
        Path dataDir = tempDir.resolve("master-managed");
        MasterServer master = new MasterServer(0, "127.0.0.1", dataDir);
        MasterHttpServer http = new MasterHttpServer(master, httpPort);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        String api = "http://127.0.0.1:" + httpPort + "/api";

        WorkerNode existing = null;
        try {
            master.start();
            http.start();

            int masterPort = master.getActualPort();
            WorkerConfig cfg = new WorkerConfig("demo-local-worker-01", "127.0.0.1", freePort(), "127.0.0.1", masterPort);
            cfg.setMachineId("demo-local");
            cfg.setMachineName("Demo Laptop");
            cfg.setHostParallelism(3);
            cfg.setGroupParallelism(3);
            cfg.setCapacity(1);
            cfg.setHeartbeatIntervalSeconds(1);
            existing = new WorkerNode(cfg);
            existing.start();
            await(() -> master.getRegistry().getAvailableWorkers().size() == 1, 8_000);

            int busyPort = freePort();
            try (ServerSocket occupied = new ServerSocket()) {
                occupied.bind(new InetSocketAddress("127.0.0.1", busyPort));

                HttpResponse<String> started = request(client, api + "/local-workers/start", "POST", Map.of(
                        "count", 2,
                        "startPort", busyPort,
                        "machineId", "demo-local",
                        "displayNamePrefix", "DemoLocal",
                        "startPaused", true
                ));
                assertEquals(200, started.statusCode(), started.body());
                JsonNode startedJson = JsonUtils.getMapper().readTree(started.body());
                assertTrue(startedJson.path("success").asBoolean());

                JsonNode batch = startedJson.path("batch");
                String batchId = batch.path("batchId").asText();
                assertTrue(batch.path("startPort").asInt() > busyPort, "Should skip occupied startPort");
                assertEquals(2, batch.path("count").asInt());
                assertTrue(batch.path("alive").asBoolean());
                assertTrue(batch.path("startPaused").asBoolean());
                assertEquals(2, batch.path("workerIds").size());
                String w1Id = batch.path("workerIds").get(0).asText();
                String w2Id = batch.path("workerIds").get(1).asText();
                assertEquals("demo-local-worker-02", w1Id);
                assertEquals("demo-local-worker-03", w2Id);

                WorkerInfo w1 = master.getRegistry().getWorker(w1Id);
                WorkerInfo w2 = master.getRegistry().getWorker(w2Id);
                assertNotNull(w1);
                assertNotNull(w2);
                assertEquals(WorkerStatus.ONLINE, w1.getStatus());
                assertEquals(WorkerStatus.ONLINE, w2.getStatus());
                assertTrue(w1.paused);
                assertTrue(w2.paused);
                assertEquals(3, w1.hostParallelism);
                assertEquals(3, w1.groupParallelism);
                assertEquals("DemoLocal-01", w1.displayName);
                assertEquals("DemoLocal-02", w2.displayName);
                assertTrue(Files.exists(dataDir.resolve("managed-workers").resolve(batchId + ".log")));
                assertTrue(Files.exists(dataDir.resolve("managed-workers").resolve(batchId + ".lock")));

                JsonNode statusJson = JsonUtils.getMapper().readTree(
                        request(client, api + "/local-workers", "GET", null).body());
                assertEquals(2, statusJson.path("activeManagedCount").asInt());
                assertEquals("demo-local", statusJson.path("defaultMachineId").asText());
                assertTrue(statusJson.path("running").asBoolean());

                HttpResponse<String> stopped = request(client, api + "/local-workers/stop", "POST",
                        Map.of("batchId", batchId));
                assertEquals(200, stopped.statusCode(), stopped.body());
                JsonNode stoppedJson = JsonUtils.getMapper().readTree(stopped.body());
                assertTrue(stoppedJson.path("stoppedBatchIds").toString().contains(batchId));
                assertTrue(stoppedJson.path("offlineWorkerIds").toString().contains(w1Id));
                assertTrue(stoppedJson.path("offlineWorkerIds").toString().contains(w2Id));
                assertEquals(WorkerStatus.OFFLINE, w1.getStatus());
                assertEquals(WorkerStatus.OFFLINE, w2.getStatus());

                JsonNode afterStop = JsonUtils.getMapper().readTree(
                        request(client, api + "/local-workers", "GET", null).body());
                assertEquals(0, afterStop.path("activeManagedCount").asInt());
            }

            // Start second batch and verify MasterServer.stop() cleans up lease and child process
            HttpResponse<String> second = request(client, api + "/local-workers/start", "POST", Map.of(
                    "count", 1,
                    "startPort", freePort(),
                    "machineId", "local-ui",
                    "startPaused", false
            ));
            assertEquals(200, second.statusCode(), second.body());
            JsonNode secondBatch = JsonUtils.getMapper().readTree(second.body()).path("batch");
            String secondBatchId = secondBatch.path("batchId").asText();
            String secondWorkerId = secondBatch.path("workerIds").get(0).asText();
            WorkerInfo secondWorker = master.getRegistry().getWorker(secondWorkerId);
            assertNotNull(secondWorker);
            assertFalse(secondWorker.paused);

            master.stop();
            assertEquals(WorkerStatus.OFFLINE, secondWorker.getStatus());
            Path secondLock = dataDir.resolve("managed-workers").resolve(secondBatchId + ".lock");
            try (SupervisorLease reacquired = SupervisorLease.acquire(secondLock)) {
                assertTrue(reacquired.isValid());
            }
        } finally {
            if (existing != null) existing.stop();
            http.stop();
            master.stop();
        }
    }

    private static HttpResponse<String> request(HttpClient client, String url, String method, Object body) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12));
        if (body == null) req.method(method, HttpRequest.BodyPublishers.noBody());
        else req.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(body)));
        return client.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "Condition not satisfied within " + timeoutMs + " ms");
    }
}
