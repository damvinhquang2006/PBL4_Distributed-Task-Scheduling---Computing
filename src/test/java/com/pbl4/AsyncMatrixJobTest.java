package com.pbl4;

import com.pbl4.computing.matrix.*;
import com.pbl4.core.model.*;
import com.pbl4.core.network.JsonUtils;
import com.pbl4.master.MasterServer;
import com.pbl4.master.http.MasterHttpServer;
import com.pbl4.worker.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class AsyncMatrixJobTest {
    private MasterServer master;
    private MasterHttpServer http;
    private WorkerNode a, b;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Task> received = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        int masterPort = LanLifecycleTest.port(), httpPort = LanLifecycleTest.port();
        master = new MasterServer(masterPort);
        master.setJobTimeoutMs(5000);
        master.start();
        http = new MasterHttpServer(master, httpPort);
        http.start();
        base = "http://127.0.0.1:" + httpPort + "/api";
        a = worker("laptop-a-worker", masterPort);
        b = worker("laptop-b-worker", masterPort);
        a.start(); b.start();
        LanLifecycleTest.await(() -> master.getRegistry().getAvailableWorkers().size() == 2, 5000);
    }

    private WorkerNode worker(String id, int mp) throws Exception {
        WorkerConfig cfg = new WorkerConfig(id, "127.0.0.1", LanLifecycleTest.port(), "127.0.0.1", mp);
        cfg.setCapacity(1);
        return new WorkerNode(cfg) {
            @Override protected TaskResult executeTask(Task task) {
                received.add(task);
                // Simulate a Worker that only understands the original v2 concrete payload.
                if (task.hasPayload("genA")) return TaskResult.failure(task.getTaskId(), task.getJobId(), id, "Unsupported descriptor payload");
                return super.executeTask(task);
            }
        };
    }

    @AfterEach void stop() {
        if (http != null) http.stop();
        if (a != null) a.stop();
        if (b != null) b.stop();
        if (master != null) master.stop();
    }

    private HttpResponse<String> post(Map<String, Object> body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/compute/async"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.getMapper().writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(String text) throws Exception {
        return JsonUtils.getMapper().readValue(text, Map.class);
    }

    private Map<String, Object> finish(String id) throws Exception {
        final Map<String, Object>[] state = new Map[]{null};
        LanLifecycleTest.await(() -> {
            state[0] = master.getJobProgress(id);
            return !"RUNNING".equals(state[0].get("status"));
        }, 6000);
        return state[0];
    }

    @SuppressWarnings("unchecked")
    @Test void webMultiplicationUsesBothWorkersAndClampsFiftyTasksToThreeRows() throws Exception {
        double[][] x = {{1,2,3},{4,5,6},{7,8,10}}, y = {{2,0,1},{1,3,0},{0,2,4}};
        Map<String, Object> request = new HashMap<>(Map.of("operation", "x", "rowsA", 3, "colsA", 3,
            "rowsB", 3, "colsB", 3, "taskCount", 50, "matrixA", x, "matrixB", y));
        // Deliberately conflicting descriptors: entered matrix values must take priority.
        request.put("genA", Map.of("generator", "constant-v1", "val", 99));
        HttpResponse<String> response = post(request);
        assertEquals(202, response.statusCode(), response.body());
        Map<String, Object> accepted = json(response.body());
        assertEquals(3, ((Number) accepted.get("totalTasks")).intValue());
        Map<String, Object> progress = finish((String) accepted.get("jobId"));
        assertEquals("COMPLETED", progress.get("status"), progress.toString());
        Map<String, Object> result = (Map<String, Object>) progress.get("resultSummary");
        assertEquals(false, result.get("isSampled"));
        assertNotNull(result.get("result"), "Small matrices must expose the field read by the direct UI grid");
        assertEquals(MatrixOperations.multiply(new Matrix(x), new Matrix(y)),
            new Matrix(JsonUtils.getMapper().convertValue(result.get("result"), double[][].class)));
        assertEquals(List.of("laptop-a-worker", "laptop-b-worker"), result.get("workerIds"));
        assertEquals(2, master.getRegistry().getAllWorkers().size());
        assertEquals(3, received.size());
        Set<Integer> starts = new HashSet<>();
        for (Task task : received) {
            int start = ((Number) task.getPayloadValue("startRow")).intValue();
            double[][] slice = JsonUtils.getMapper().convertValue(task.getPayloadValue("sliceA"), double[][].class);
            assertTrue(start >= 0 && start + slice.length <= 3);
            assertTrue(starts.add(start));
            assertTrue(task.hasPayload("dataB"));
        }
        HttpResponse<String> polled = client.send(HttpRequest.newBuilder(URI.create(base + "/jobs/progress?jobId=" + accepted.get("jobId"))).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("COMPLETED", json(polled.body()).get("status"));
    }

    @SuppressWarnings("unchecked")
    @Test void descriptorJobsComputeRealRectangularProductsAndScalarResults() throws Exception {
        HttpResponse<String> response = post(Map.of("operation", "x", "rowsA", 5, "colsA", 3,
            "rowsB", 3, "colsB", 2, "genA", Map.of("generator", "constant-v1", "val", 2),
            "genB", Map.of("generator", "constant-v1", "val", 4)));
        assertEquals(202, response.statusCode(), response.body());
        Map<String, Object> progress = finish((String) json(response.body()).get("jobId"));
        assertEquals("COMPLETED", progress.get("status"), progress.toString());
        Map<String, Object> summary = (Map<String, Object>) progress.get("resultSummary");
        double[][] grid = JsonUtils.getMapper().convertValue(summary.get("sampleGrid"), double[][].class);
        assertEquals(5, grid.length);
        for (double[] row : grid) assertArrayEquals(new double[]{24,24}, row);
        for (String op : List.of("det", "rank")) {
            response = post(Map.of("operation", op, "rowsA", 3, "colsA", 3,
                "matrixA", new double[][]{{1,2,3},{2,4,6},{3,6,9}}));
            assertEquals(202, response.statusCode(), response.body());
            progress = finish((String) json(response.body()).get("jobId"));
            assertEquals("COMPLETED", progress.get("status"), progress.toString());
            summary = (Map<String, Object>) progress.get("resultSummary");
            assertEquals(op.equals("det") ? 0.0 : 1.0, ((Number) summary.get("resultScalar")).doubleValue(), 1e-9);
        }
    }

    @Test void rejectsBadDimensionsAndOversizedMatricesBeforeQueuing() throws Exception {
        for (Map<String, Object> request : List.of(
                Map.<String,Object>of("operation", "x", "rowsA", 3, "colsA", 2, "rowsB", 3, "colsB", 3),
                Map.<String,Object>of("operation", "+", "rowsA", 0),
                Map.<String,Object>of("operation", "scalar", "rowsA", 50000, "colsA", 50000),
                Map.<String,Object>of("operation", "+", "rowsA", 3, "colsA", 3, "matrixA", new double[][]{{1}}))) {
            HttpResponse<String> response = post(request);
            assertEquals(400, response.statusCode(), response.body());
            assertNotNull(json(response.body()).get("error"));
        }
        assertTrue(received.isEmpty());
        assertEquals(2, master.getRegistry().getAllWorkers().size());
    }

    @SuppressWarnings("unchecked")
    @Test void otherWebOperationsUseTheSameExactMathAsTheConsole() throws Exception {
        Matrix x = new Matrix(new double[][]{{4,1,2},{0,3,1},{1,0,2}});
        Matrix y = new Matrix(new double[][]{{2,3,4},{1,2,3},{4,1,2}});
        for (String op : List.of("+", "-", "%", "scalar", "t", "inv")) {
            Map<String, Object> body = new HashMap<>(Map.of("operation", op, "rowsA", 3, "colsA", 3,
                "rowsB", 3, "colsB", 3, "matrixA", x.getData(), "matrixB", y.getData(), "scalar", 2.5));
            HttpResponse<String> response = post(body);
            assertEquals(202, response.statusCode(), response.body());
            Map<String, Object> progress = finish((String) json(response.body()).get("jobId"));
            assertEquals("COMPLETED", progress.get("status"), progress.toString());
            Map<String, Object> summary = (Map<String, Object>) progress.get("resultSummary");
            Matrix actual = new Matrix(JsonUtils.getMapper().convertValue(summary.get("sampleGrid"), double[][].class));
            Matrix expected = switch (op) {
                case "+" -> MatrixOperations.add(x, y);
                case "-" -> MatrixOperations.subtract(x, y);
                case "%" -> MatrixOperations.modulo(x, y);
                case "scalar" -> MatrixOperations.multiplyScalar(x, 2.5);
                case "t" -> MatrixOperations.transpose(x);
                default -> MatrixOperations.inverse(x);
            };
            assertEquals(expected, actual, op);
        }
    }

    @Test void preservesWorkerComputationErrorInsteadOfGenericTimeout() throws Exception {
        HttpResponse<String> response = post(Map.of("operation", "inv", "rowsA", 2, "colsA", 2,
            "matrixA", new double[][]{{1,2},{2,4}}));
        Map<String, Object> progress = finish((String) json(response.body()).get("jobId"));
        assertEquals("FAILED", progress.get("status"));
        String error = (String) progress.get("error");
        assertNotNull(error);
        assertFalse(error.contains("timeout"), error);
        assertFalse(error.contains("sliceA"), error);
    }

    @Test void absentWorkersCauseTimeoutWithoutSpawningLocalWorkers() throws Exception {
        MasterServer empty = new MasterServer(LanLifecycleTest.port());
        try {
            empty.start(); empty.setJobTimeoutMs(250);
            MasterServer.AsyncJobRecord rec = empty.submitAsyncMatrixJob("x", 3,3,3,3,
                Map.of("generator", "identity-v1"), Map.of("generator", "identity-v1"), null, 50);
            LanLifecycleTest.await(() -> rec.status == JobStatus.FAILED, 2000);
            assertNotNull(rec.error);
            assertTrue(empty.getRegistry().getAllWorkers().isEmpty());
        } finally { empty.stop(); }
    }

    @Test void connectedButPausedWorkersExplainWhyNoComputationStarts() throws Exception {
        master.toggleWorkerState("laptop-a-worker");
        master.toggleWorkerState("laptop-b-worker");
        master.setJobTimeoutMs(700);
        HttpResponse<String> response = post(Map.of("operation", "x", "rowsA", 2, "colsA", 2,
            "rowsB", 2, "colsB", 2, "genA", Map.of("generator", "identity-v1"), "genB", Map.of("generator", "identity-v1")));
        String id = (String) json(response.body()).get("jobId");
        LanLifecycleTest.await(() -> master.getJobProgress(id).containsKey("waitingReason"), 500);
        assertTrue(master.getJobProgress(id).get("waitingReason").toString().contains("PAUSED"));
        Map<String,Object> progress = finish(id);
        assertEquals("FAILED", progress.get("status"));
        assertTrue(progress.get("error").toString().contains("PAUSED"));
        assertTrue(received.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test void eightAndHundredRowMatricesReturnEveryCellAndBothWorkersCompute() throws Exception {
        master.setJobTimeoutMs(15_000);
        for (int size : List.of(8, 100)) {
            received.clear();
            HttpResponse<String> response = post(Map.of("operation", "x", "rowsA", size, "colsA", size,
                "rowsB", size, "colsB", size, "genA", Map.of("generator", "constant-v1", "val", 3),
                "genB", Map.of("generator", "identity-v1")));
            assertEquals(202, response.statusCode(), response.body());
            String id = (String) json(response.body()).get("jobId");
            LanLifecycleTest.await(() -> !"RUNNING".equals(master.getJobProgress(id).get("status")), 16_000);
            Map<String,Object> progress = master.getJobProgress(id);
            assertEquals("COMPLETED", progress.get("status"), progress.toString());
            Map<String,Object> summary = (Map<String,Object>) progress.get("resultSummary");
            assertEquals(false, summary.get("isSampled"));
            double[][] result = JsonUtils.getMapper().convertValue(summary.get("result"), double[][].class);
            assertNotNull(result);
            assertEquals(size, result.length);
            for (double[] row : result) {
                assertEquals(size, row.length);
                for (double value : row) assertEquals(3.0, value, 1e-9);
            }
            assertEquals(List.of("laptop-a-worker", "laptop-b-worker"), summary.get("workerIds"));
            assertEquals(((Number) progress.get("totalTasks")).intValue(), received.size());
            assertEquals(2, master.getRegistry().getAllWorkers().size());
        }
    }

    @SuppressWarnings("unchecked")
    @Test void remainingWorkerCanFinishHundredByHundredAfterOtherGoesOffline() throws Exception {
        b.stop();
        master.getRegistry().getWorker("laptop-b-worker").setLastHeartbeatTime(System.currentTimeMillis() - 10_000);
        master.getRegistry().checkHealth();
        HttpResponse<String> response = post(Map.of("operation", "x", "rowsA", 100, "colsA", 100,
            "rowsB", 100, "colsB", 100, "genA", Map.of("generator", "constant-v1", "val", 2),
            "genB", Map.of("generator", "identity-v1")));
        assertEquals(202, response.statusCode(), response.body());
        Map<String,Object> progress = finish((String) json(response.body()).get("jobId"));
        assertEquals("COMPLETED", progress.get("status"), progress.toString());
        Map<String,Object> summary = (Map<String,Object>) progress.get("resultSummary");
        double[][] result = JsonUtils.getMapper().convertValue(summary.get("result"), double[][].class);
        assertEquals(100, result.length);
        assertEquals(2.0, result[99][99]);
        assertEquals(List.of("laptop-a-worker"), summary.get("workerIds"));
    }
}
