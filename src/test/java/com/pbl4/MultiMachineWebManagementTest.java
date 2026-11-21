package com.pbl4;

import com.fasterxml.jackson.databind.JsonNode;
import com.pbl4.app.WorkerClusterLauncher;
import com.pbl4.core.model.Job;
import com.pbl4.core.model.Task;
import com.pbl4.core.model.TaskType;
import com.pbl4.core.model.WorkerInfo;
import com.pbl4.core.model.WorkerStatus;
import com.pbl4.core.network.JsonUtils;
import com.pbl4.master.MasterServer;
import com.pbl4.master.http.MasterHttpServer;
import com.pbl4.worker.WorkerConfig;
import com.pbl4.worker.WorkerNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class MultiMachineWebManagementTest {
    @TempDir Path tempDir;

    @Test void twoMachineFleetIsSharedRenameableAndControllableFromRemoteWebOrigin() throws Exception {
        int masterPort = LanLifecycleTest.port();
        int httpPort = LanLifecycleTest.port();
        MasterServer master = new MasterServer(masterPort, "0.0.0.0", tempDir.resolve("master-state"));
        MasterHttpServer http = new MasterHttpServer(master, httpPort);
        WorkerNode laptopA = worker("a-worker-01", "127.0.0.2", "laptop-a", "Laptop A", masterPort);
        WorkerNode laptopB = worker("b-worker-01", "127.0.0.3", "laptop-b", "Laptop B", masterPort);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        String remoteOrigin = "http://127.0.0.2:5173";
        String secondBrowserOrigin = "http://127.0.0.2:5174";
        String api = "http://127.0.0.2:" + httpPort + "/api";

        try {
            master.start();
            http.start();
            laptopA.start();
            laptopB.start();
            LanLifecycleTest.await(() -> master.getRegistry().getAvailableWorkers().size() == 2, 8_000);

            HttpResponse<String> clusterResponse = request(client, api + "/cluster", "GET", null, remoteOrigin);
            assertEquals(200, clusterResponse.statusCode());
            assertEquals(remoteOrigin, clusterResponse.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
            JsonNode cluster = JsonUtils.getMapper().readTree(clusterResponse.body());
            assertEquals(2, cluster.path("totalWorkers").asInt());
            assertEquals(2, cluster.path("machines").size());
            assertEquals("laptop-a", cluster.path("workers").get(0).path("machineId").asText());
            assertTrue(cluster.path("workers").toString().contains("b-worker-01"));

            HttpResponse<String> rename = request(client, api + "/workers/rename", "POST",
                Map.of("workerId", "b-worker-01", "displayName", "Worker B – máy tính từ xa"), remoteOrigin);
            assertEquals(200, rename.statusCode(), rename.body());
            cluster = JsonUtils.getMapper().readTree(request(client, api + "/cluster", "GET", null, remoteOrigin).body());
            assertEquals("Worker B – máy tính từ xa", findWorker(cluster, "b-worker-01").path("displayName").asText());
            assertEquals(2, cluster.path("totalWorkers").asInt(), "Changing a display name must not change the worker count");
            JsonNode secondBrowserCluster = JsonUtils.getMapper().readTree(
                request(client, api + "/cluster", "GET", null, secondBrowserOrigin).body());
            assertEquals("Worker B – máy tính từ xa", findWorker(secondBrowserCluster, "b-worker-01").path("displayName").asText(),
                "A second browser session must read the same shared alias");

            HttpResponse<String> pause = request(client, api + "/workers/toggle", "POST",
                Map.of("workerId", "b-worker-01"), remoteOrigin);
            assertEquals(200, pause.statusCode(), pause.body());
            LanLifecycleTest.await(() -> !laptopB.isEnabled(), 3_000);
            cluster = JsonUtils.getMapper().readTree(request(client, api + "/cluster", "GET", null, secondBrowserOrigin).body());
            assertTrue(findWorker(cluster, "b-worker-01").path("paused").asBoolean());
            assertFalse(findWorker(cluster, "a-worker-01").path("paused").asBoolean());

            HttpResponse<String> resume = request(client, api + "/workers/toggle", "POST",
                Map.of("workerId", "b-worker-01"), remoteOrigin);
            assertEquals(200, resume.statusCode(), resume.body());
            LanLifecycleTest.await(laptopB::isEnabled, 3_000);

            HttpResponse<String> deniedOrigin = request(client, api + "/cluster", "OPTIONS", null,
                "http://attacker.example:5173");
            assertEquals(403, deniedOrigin.statusCode(), "A different web host must not receive cross-origin API access");
            HttpResponse<String> blankName = request(client, api + "/workers/rename", "POST",
                Map.of("workerId", "b-worker-01", "displayName", "   "), remoteOrigin);
            assertEquals(400, blankName.statusCode());
        } finally {
            laptopA.stop();
            laptopB.stop();
            http.stop();
            master.stop();
        }
    }

    @Test void batchStateControlStartPausedReconnectAndDisplayNamePropagation() throws Exception {
        int masterPort = LanLifecycleTest.port();
        int httpPort = LanLifecycleTest.port();
        MasterServer master = new MasterServer(masterPort, "0.0.0.0", tempDir.resolve("master-state-batch"));
        MasterHttpServer http = new MasterHttpServer(master, httpPort);
        WorkerNode laptopA = worker("a-worker-01", "127.0.0.1", "laptop-a", "Laptop A", masterPort);
        WorkerNode laptopB = worker("b-worker-01", "127.0.0.1", "laptop-b", "Laptop B", masterPort);
        WorkerClusterLauncher.Cluster standbyCluster = null;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        String origin = "http://127.0.0.1:5173";
        String api = "http://127.0.0.1:" + httpPort + "/api";

        try {
            master.start();
            http.start();
            laptopA.start();
            laptopB.start();
            int standbyPort = freePort();
            standbyCluster = WorkerClusterLauncher.launch(1, standbyPort, "127.0.0.1", "127.0.0.1", masterPort,
                1, "worker-", "laptop-c", "Laptop C", "127.0.0.1", 2, 2, 0, "laptop-c-group-01", 2, true, false, true);
            LanLifecycleTest.await(() -> master.getRegistry().getAllWorkers().size() == 3, 8_000);

            String standbyId = "laptop-c-worker-01";
            WorkerInfo standbyInfo = master.getRegistry().getWorker(standbyId);
            assertNotNull(standbyInfo);
            assertTrue(standbyInfo.paused, "Worker launched with --startPaused must start paused");
            assertFalse(standbyCluster.getWorkers().get(0).isEnabled());

            // 400 validation checks for /api/workers/state
            assertEquals(400, request(client, api + "/workers/state", "POST", Map.of(), origin).statusCode());
            assertEquals(400, request(client, api + "/workers/state", "POST", Map.of("action", "PAUSE"), origin).statusCode());
            assertEquals(400, request(client, api + "/workers/state", "POST", Map.of("action", "PAUSE_OTHERS"), origin).statusCode());
            assertEquals(400, request(client, api + "/workers/state", "POST", Map.of("action", "UNKNOWN"), origin).statusCode());

            // Rename workers and check describeAvailability & benchmark/limit/failover propagation
            assertEquals(200, request(client, api + "/workers/rename", "POST",
                Map.of("workerId", "a-worker-01", "displayName", "Máy A Chính"), origin).statusCode());
            assertEquals(200, request(client, api + "/workers/rename", "POST",
                Map.of("workerId", "b-worker-01", "displayName", "Máy B Phụ"), origin).statusCode());
            assertTrue(master.getScheduler().describeAvailability().contains("Máy A Chính [a-worker-01]"));

            // Mark laptop-b OFFLINE so skippedOfflineIds is exercised
            master.getRegistry().markWorkersOffline(List.of("b-worker-01"));

            // RESUME_ALL resumes standby worker and laptopA while skipping offline laptopB
            HttpResponse<String> resumeAll = request(client, api + "/workers/state", "POST",
                Map.of("action", "RESUME_ALL"), origin);
            assertEquals(200, resumeAll.statusCode(), resumeAll.body());
            JsonNode resumeAllJson = JsonUtils.getMapper().readTree(resumeAll.body());
            assertTrue(resumeAllJson.path("updatedWorkerIds").toString().contains(standbyId));
            assertTrue(resumeAllJson.path("skippedOfflineIds").toString().contains("b-worker-01"));
            assertEquals(2, resumeAllJson.path("activeCount").asInt());
            assertEquals(0, resumeAllJson.path("pausedCount").asInt());
            WorkerClusterLauncher.Cluster currentStandby = standbyCluster;
            LanLifecycleTest.await(() -> currentStandby.getWorkers().get(0).isEnabled(), 3_000);

            // Reconnect standby worker with startPaused=true in a new session: old.paused (false) must be preserved
            standbyCluster.close();
            standbyCluster = WorkerClusterLauncher.launch(1, standbyPort, "127.0.0.1", "127.0.0.1", masterPort,
                1, "worker-", "laptop-c", "Laptop C", "127.0.0.1", 2, 2, 0, "laptop-c-group-01", 2, true, false, true);
            WorkerClusterLauncher.Cluster reconnectedStandby = standbyCluster;
            LanLifecycleTest.await(() -> reconnectedStandby.registeredCount() == 1, 8_000);
            assertFalse(master.getRegistry().getWorker(standbyId).paused,
                "Reconnecting worker must retain Master-controlled paused state (false) instead of startPaused");

            // PAUSE_OTHERS keeping machineId="laptop-a" pauses standbyId and skips offline b-worker-01
            HttpResponse<String> pauseOthers = request(client, api + "/workers/state", "POST",
                Map.of("action", "PAUSE_OTHERS", "machineId", "laptop-a"), origin);
            assertEquals(200, pauseOthers.statusCode(), pauseOthers.body());
            JsonNode pauseOthersJson = JsonUtils.getMapper().readTree(pauseOthers.body());
            assertTrue(pauseOthersJson.path("updatedWorkerIds").toString().contains(standbyId));
            assertFalse(pauseOthersJson.path("updatedWorkerIds").toString().contains("a-worker-01"));
            assertTrue(pauseOthersJson.path("skippedOfflineIds").toString().contains("b-worker-01"));
            assertEquals(1, pauseOthersJson.path("activeCount").asInt());
            assertEquals(1, pauseOthersJson.path("pausedCount").asInt());
            assertFalse(master.getRegistry().getWorker("a-worker-01").paused);
            assertTrue(master.getRegistry().getWorker(standbyId).paused);

            // Check /api/workers/limit includes activeWorkers with displayName
            HttpResponse<String> limitResp = request(client, api + "/workers/limit", "POST", Map.of("count", 1), origin);
            assertEquals(200, limitResp.statusCode(), limitResp.body());
            JsonNode limitJson = JsonUtils.getMapper().readTree(limitResp.body());
            assertEquals("a-worker-01", limitJson.path("activeWorkers").get(0).path("workerId").asText());
            assertEquals("Máy A Chính", limitJson.path("activeWorkers").get(0).path("displayName").asText());

            // Check /api/benchmark/run includes activeWorkers with displayName
            HttpResponse<String> benchResp = request(client, api + "/benchmark/run", "POST",
                Map.of("problemType", "matrix_add", "size", 8, "workerCount", 1, "compareSequential", false), origin);
            assertEquals(200, benchResp.statusCode(), benchResp.body());
            JsonNode benchJson = JsonUtils.getMapper().readTree(benchResp.body());
            assertEquals("Máy A Chính", benchJson.path("activeWorkers").get(0).path("displayName").asText());

            // Check /api/cluster/failover-test includes victimDisplayName
            HttpResponse<String> failoverResp = request(client, api + "/cluster/failover-test", "POST", Map.of(), origin);
            assertEquals(200, failoverResp.statusCode(), failoverResp.body());
            JsonNode failoverJson = JsonUtils.getMapper().readTree(failoverResp.body());
            assertEquals("a-worker-01", failoverJson.path("victimWorkerId").asText());
            assertEquals("Máy A Chính", failoverJson.path("victimDisplayName").asText());
            assertTrue(failoverJson.path("message").asText().contains("Máy A Chính"));

            // Check scale50 still returns 410 Gone
            HttpResponse<String> scale50 = request(client, api + "/cluster/scale50", "POST", Map.of(), origin);
            assertEquals(410, scale50.statusCode());

            // Verify failoverEvents carry live displayName in getJobProgress
            master.setWorkersState("RESUME_ALL", null, null);
            assertEquals(200, request(client, api + "/workers/rename", "POST",
                Map.of("workerId", standbyId, "displayName", "Máy C Dự Phòng"), origin).statusCode());
            laptopA.getConfig().setDemoDelay(true);
            standbyCluster.getWorkers().get(0).getConfig().setDemoDelay(true);
            var asyncRec = master.submitAsyncMatrixJob("+", 16, 16, 16, 16,
                Map.of("generator", "constant-v1", "val", 1.0),
                Map.of("generator", "constant-v1", "val", 2.0), null, 4);
            LanLifecycleTest.await(() -> master.getRegistry().getWorker(standbyId).getActiveTasks() > 0, 5_000);
            master.getRegistry().markWorkersOffline(List.of(standbyId));
            laptopA.getConfig().setDemoDelay(false);
            LanLifecycleTest.await(() -> !"RUNNING".equals(master.getJobProgress(asyncRec.jobId).get("status")), 10_000);
            assertEquals(200, request(client, api + "/workers/rename", "POST",
                Map.of("workerId", standbyId, "displayName", "Máy C Đã Đổi Tên"), origin).statusCode());
            Map<String, Object> progress = master.getJobProgress(asyncRec.jobId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> failoverEvents = (List<Map<String, Object>>) progress.get("failoverEvents");
            assertFalse(failoverEvents.isEmpty(), "Expected failover event when active standby worker went offline");
            assertTrue(failoverEvents.stream().anyMatch(evt ->
                standbyId.equals(evt.get("workerId")) && "Máy C Đã Đổi Tên".equals(evt.get("displayName"))),
                "failoverEvents must reflect live displayName: " + failoverEvents);
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) progress.get("resultSummary");
            @SuppressWarnings("unchecked")
            Map<String, String> summaryNames = (Map<String, String>) summary.get("workerDisplayNames");
            assertEquals("Máy A Chính", summaryNames.get("a-worker-01"));
        } finally {
            if (standbyCluster != null) standbyCluster.close();
            laptopA.stop();
            laptopB.stop();
            http.stop();
            master.stop();
        }
    }

    private static JsonNode findWorker(JsonNode cluster, String workerId) {
        for (JsonNode worker : cluster.path("workers"))
            if (workerId.equals(worker.path("workerId").asText())) return worker;
        fail("Worker not found in shared cluster view: " + workerId);
        return null;
    }

    private static HttpResponse<String> request(HttpClient client, String url, String method,
                                                Object body, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4))
            .header("Origin", origin);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static WorkerNode worker(String id, String host, String machineId, String machineName, int masterPort) {
        WorkerConfig config = new WorkerConfig(id, host, freePort(), "127.0.0.2", masterPort);
        config.setMachineId(machineId);
        config.setMachineName(machineName);
        config.setHostParallelism(2);
        config.setCapacity(1);
        config.setHeartbeatIntervalSeconds(1);
        return new WorkerNode(config);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
