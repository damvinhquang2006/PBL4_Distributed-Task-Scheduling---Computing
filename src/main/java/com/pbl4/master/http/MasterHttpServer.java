package com.pbl4.master.http;

import com.pbl4.computing.graph.Graph;
import com.pbl4.computing.graph.ShortestPathAlgorithms;
import com.pbl4.computing.matrix.Matrix;
import com.pbl4.computing.matrix.MatrixOperations;
import com.pbl4.core.model.LoadBalancingPolicy;
import com.pbl4.core.model.WorkerInfo;
import com.pbl4.core.model.WorkerStatus;
import com.pbl4.core.network.JsonUtils;
import com.pbl4.master.MasterServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP REST API Server cho Master de ket noi voi Frontend ReactJS.
 * Cung cap cac endpoint giam sat cum, can bang tai va giai ma tran truc quan.
 */
public class MasterHttpServer {
    private static final int MAX_HTTP_BODY_BYTES = 32 * 1024 * 1024;
    private final MasterServer masterServer;
    private final int port;
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;

    public MasterHttpServer(MasterServer masterServer, int port) {
        this.masterServer = masterServer;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);

        // Dang ky cac endpoint API
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/cluster", this::handleCluster);
        server.createContext("/api/workers", this::handleWorkers);
        server.createContext("/api/workers/toggle", this::handleToggleWorker);
        server.createContext("/api/policy", this::handlePolicy);
        server.createContext("/api/compute", this::handleCompute);
        server.createContext("/api/compute/async", this::handleComputeAsync);
        server.createContext("/api/jobs/progress", this::handleJobProgress);
        server.createContext("/api/jobs/cancel", this::handleJobCancel);
        server.createContext("/api/jobs/result", this::handleJobResult);
        server.createContext("/api/jobs", this::handleJobList);
        server.createContext("/api/cluster/scale50", this::handleScale50);
        server.createContext("/api/cluster/failover-test", this::handleFailoverTest);
        server.createContext("/api/starter", this::handleStarter);
        server.createContext("/api/workers/limit", this::handleWorkersLimit);
        server.createContext("/api/benchmark/run", this::handleBenchmarkRun);

        server.start();
        System.out.printf("[MasterHttpServer] REST API Server san sang tai: http://localhost:%d/api/%n", port);
    }

    private void handleJobList(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed")); return;
        }
        sendResponse(exchange, 200, Map.of("jobs", masterServer.getAsyncJobList()));
    }

    private void handleJobResult(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed")); return;
        }
        String jobId = queryValue(exchange, "jobId");
        if (jobId == null || jobId.isBlank()) { sendResponse(exchange, 400, Map.of("error", "jobId is required")); return; }
        try {
            int startRow = Integer.parseInt(Optional.ofNullable(queryValue(exchange, "startRow")).orElse("0"));
            int rowCount = Integer.parseInt(Optional.ofNullable(queryValue(exchange, "rowCount")).orElse("64"));
            int startCol = Integer.parseInt(Optional.ofNullable(queryValue(exchange, "startCol")).orElse("0"));
            int colCount = Integer.parseInt(Optional.ofNullable(queryValue(exchange, "colCount")).orElse("256"));
            Map<String, Object> page = masterServer.getAsyncResultPage(jobId, startRow, rowCount, startCol, colCount);
            if (page == null) sendResponse(exchange, 404, Map.of("error", "Job not found"));
            else sendResponse(exchange, 200, page);
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, Map.of("error", "Invalid startRow or rowCount"));
        }
    }

    private String queryValue(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            if (equals > 0 && key.equals(part.substring(0, equals)))
                return java.net.URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            executor.shutdownNow();
            System.out.println("[MasterHttpServer] Da dung HTTP Server an toan.");
        }
    }

    private void handleCluster(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { sendResponse(exchange, 405, Map.of("error", "Method Not Allowed")); return; }
        Map<String, Object> snapshot = masterServer.getClusterSnapshot();
        snapshot.put("httpPort", port);
        sendResponse(exchange, 200, snapshot);
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { sendResponse(exchange, 405, Map.of("error", "Method Not Allowed")); return; }
        Map<String, Object> snapshot = masterServer.getClusterSnapshot();
        snapshot.remove("workers"); snapshot.remove("machines"); snapshot.put("httpPort", port);
        sendResponse(exchange, 200, snapshot);
    }

    private void handleWorkers(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { sendResponse(exchange, 405, Map.of("error", "Method Not Allowed")); return; }
        sendResponse(exchange, 200, masterServer.getClusterSnapshot().get("workers"));
    }
    private void handleToggleWorker(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        Map<String, Object> body = parseBody(exchange);
        String workerId = (String) body.get("workerId");
        if (workerId == null || workerId.isBlank()) {
            sendResponse(exchange, 400, Map.of("error", "workerId is required"));
            return;
        }

        boolean success = masterServer.toggleWorkerState(workerId);
        WorkerInfo w = masterServer.getRegistry().getWorker(workerId);
        sendResponse(exchange, 200, Map.of(
                "success", success,
                "workerId", workerId,
                "paused", w != null && w.paused
        ));
    }

    private void handlePolicy(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        Map<String, Object> body = parseBody(exchange);
        String policyStr = (String) body.get("policy");
        try {
            LoadBalancingPolicy policy = LoadBalancingPolicy.valueOf(policyStr);
            masterServer.getScheduler().setPolicy(policy);
            sendResponse(exchange, 200, Map.of("success", true, "policy", policy.name()));
        } catch (Exception e) {
            sendResponse(exchange, 400, Map.of("error", "Invalid policy: " + policyStr));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleCompute(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        try {
            Map<String, Object> body = parseBody(exchange);
            if (Boolean.TRUE.equals(body.get("isLargeScale"))) {
                sendResponse(exchange, 410, Map.of(
                        "error", "API tính toán ước lượng đã bị loại bỏ. Hãy dùng /api/compute/async để chạy phép tính phân tán thật.",
                        "code", "LEGACY_ESTIMATE_REMOVED",
                        "use", "/api/compute/async"));
                return;
            }
            String op = ((String) body.getOrDefault("operation", "+")).trim();
            List<List<Number>> rawA = (List<List<Number>>) body.get("matrixA");
            List<List<Number>> rawB = (List<List<Number>>) body.get("matrixB");
            boolean compare = Boolean.TRUE.equals(body.get("compareSequential"));
            Number scalarNum = (Number) body.get("scalar");
            double scalar = scalarNum != null ? scalarNum.doubleValue() : 2.0;
            if (!Double.isFinite(scalar)) throw new IllegalArgumentException("Hệ số phải là số hữu hạn.");

            if (rawA == null || rawA.isEmpty()) {
                sendResponse(exchange, 400, Map.of("error", "matrixA must not be empty"));
                return;
            }

            Matrix A = toMatrix(rawA);
            Matrix B = (rawB != null && !rawB.isEmpty()) ? toMatrix(rawB) : null;

            boolean isUnary = "T".equalsIgnoreCase(op) || "transpose".equalsIgnoreCase(op)
                    || "inv".equalsIgnoreCase(op) || "inverse".equalsIgnoreCase(op)
                    || "det".equalsIgnoreCase(op) || "determinant".equalsIgnoreCase(op)
                    || "rank".equalsIgnoreCase(op)
                    || "scalar".equalsIgnoreCase(op) || "*s".equalsIgnoreCase(op) || "k*A".equalsIgnoreCase(op);

            if (!isUnary) {
                if (B == null) {
                    sendResponse(exchange, 400, Map.of("error", "matrixB must not be empty for binary operations"));
                    return;
                }
                // Check dimensions for binary
                if ("+".equals(op) || "-".equals(op) || "%".equals(op)) {
                    if (A.getRows() != B.getRows() || A.getCols() != B.getCols()) {
                        sendResponse(exchange, 400, Map.of("error", String.format(
                                "Kích thước không hợp lệ cho phép tính '%s': A là [%d x %d], B là [%d x %d]",
                                op, A.getRows(), A.getCols(), B.getRows(), B.getCols())));
                        return;
                    }
                } else if ("*".equalsIgnoreCase(op) || "x".equalsIgnoreCase(op)) {
                    if (A.getCols() != B.getRows()) {
                        sendResponse(exchange, 400, Map.of("error", String.format(
                                "Kích thước không hợp lệ cho phép Nhân (x): Số cột của A (%d) phải bằng số dòng của B (%d)",
                                A.getCols(), B.getRows())));
                        return;
                    }
                    if ((long) A.getRows() * B.getCols() > 1_000_000) {
                        sendResponse(exchange, 400, Map.of("error", "Ma trận kết quả hỗ trợ tối đa 1.000.000 phần tử.")); return;
                    }
                } else {
                    sendResponse(exchange, 400, Map.of("error", "Unsupported operation: " + op));
                    return;
                }
            } else {
                // Check dimensions for unary square operations
                if ("inv".equalsIgnoreCase(op) || "inverse".equalsIgnoreCase(op)
                        || "det".equalsIgnoreCase(op) || "determinant".equalsIgnoreCase(op)) {
                    if (A.getRows() != A.getCols()) {
                        sendResponse(exchange, 400, Map.of("error", String.format(
                                "Phép toán '%s' yêu cầu ma trận vuông: A là [%d x %d]",
                                op, A.getRows(), A.getCols())));
                        return;
                    }
                }
            }

            // 1. Tinh toan phan tan
            long distStart = System.nanoTime();
            Matrix distMatrix = null;
            Double distScalar = null;
            boolean isScalarResult = false;

            if ("+".equals(op)) {
                distMatrix = masterServer.runMatrixAddOrSub(A, B, true);
            } else if ("-".equals(op)) {
                distMatrix = masterServer.runMatrixAddOrSub(A, B, false);
            } else if ("%".equals(op)) {
                distMatrix = masterServer.runMatrixModulo(A, B);
            } else if ("*".equalsIgnoreCase(op) || "x".equalsIgnoreCase(op)) {
                distMatrix = masterServer.runMatrixMultiplication(A, B);
            } else if ("scalar".equalsIgnoreCase(op) || "*s".equalsIgnoreCase(op) || "k*A".equalsIgnoreCase(op)) {
                distMatrix = masterServer.runMatrixScalarMultiplication(A, scalar);
            } else if ("T".equalsIgnoreCase(op) || "transpose".equalsIgnoreCase(op)) {
                distMatrix = masterServer.runMatrixTranspose(A);
            } else if ("inv".equalsIgnoreCase(op) || "inverse".equalsIgnoreCase(op)) {
                distMatrix = masterServer.runMatrixInverse(A);
            } else if ("det".equalsIgnoreCase(op) || "determinant".equalsIgnoreCase(op)) {
                distScalar = masterServer.runMatrixDeterminant(A);
                isScalarResult = true;
            } else if ("rank".equalsIgnoreCase(op)) {
                Integer r = masterServer.runMatrixRank(A);
                distScalar = r != null ? r.doubleValue() : null;
                isScalarResult = true;
            } else {
                sendResponse(exchange, 400, Map.of("error", "Unsupported operation: " + op));
                return;
            }
            double distTimeMs = (System.nanoTime() - distStart) / 1_000_000.0;

            if (!isScalarResult && distMatrix == null) {
                sendResponse(exchange, 500, Map.of("error", "Distributed computation failed or timed out."));
                return;
            }
            if (isScalarResult && distScalar == null) {
                sendResponse(exchange, 500, Map.of("error", "Distributed computation failed or timed out."));
                return;
            }

            // 2. Tinh toan tuan tu (neu co yeu cau so sanh)
            Double seqTimeMs = null;
            Double speedup = null;
            if (compare) {
                long seqStart = System.nanoTime();
                if ("+".equals(op)) {
                    MatrixOperations.add(A, B);
                } else if ("-".equals(op)) {
                    MatrixOperations.subtract(A, B);
                } else if ("%".equals(op)) {
                    MatrixOperations.modulo(A, B);
                } else if ("*".equalsIgnoreCase(op) || "x".equalsIgnoreCase(op)) {
                    MatrixOperations.multiply(A, B);
                } else if ("scalar".equalsIgnoreCase(op) || "*s".equalsIgnoreCase(op) || "k*A".equalsIgnoreCase(op)) {
                    MatrixOperations.multiplyScalar(A, scalar);
                } else if ("T".equalsIgnoreCase(op) || "transpose".equalsIgnoreCase(op)) {
                    MatrixOperations.transpose(A);
                } else if ("inv".equalsIgnoreCase(op) || "inverse".equalsIgnoreCase(op)) {
                    MatrixOperations.inverse(A);
                } else if ("det".equalsIgnoreCase(op) || "determinant".equalsIgnoreCase(op)) {
                    MatrixOperations.determinant(A);
                } else if ("rank".equalsIgnoreCase(op)) {
                    MatrixOperations.rank(A);
                }
                seqTimeMs = (System.nanoTime() - seqStart) / 1_000_000.0;
                speedup = (seqTimeMs > 0 && distTimeMs > 0) ? (seqTimeMs / distTimeMs) : 1.0;
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("operation", op);
            resp.put("isScalar", isScalarResult);
            if (isScalarResult) {
                resp.put("resultScalar", distScalar);
                resp.put("rows", A.getRows());
                resp.put("cols", A.getCols());
            } else {
                resp.put("rows", distMatrix.getRows());
                resp.put("cols", distMatrix.getCols());
                resp.put("result", distMatrix.getData());
            }
            resp.put("distributedTimeMs", Math.round(distTimeMs * 100.0) / 100.0);
            resp.put("sequentialTimeMs", seqTimeMs != null ? Math.round(seqTimeMs * 100.0) / 100.0 : null);
            resp.put("speedup", speedup != null ? Math.round(speedup * 1000.0) / 1000.0 : null);
            resp.put("workersOnline", masterServer.getRegistry().getAvailableWorkers().size());

            sendResponse(exchange, 200, resp);

        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, Map.of("error", e.getMessage() == null ? "Dữ liệu yêu cầu không hợp lệ." : e.getMessage()));
        } catch (Exception e) {
            sendResponse(exchange, 500, Map.of("error", "Error executing computation: " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    public static double generateCellValue(String genType, long seed, int row, int col, double min, double max, double constVal) {
        if ("identity".equalsIgnoreCase(genType) || "identity-v1".equalsIgnoreCase(genType)) {
            return row == col ? 1.0 : 0.0;
        }
        if ("constant".equalsIgnoreCase(genType) || "constant-v1".equalsIgnoreCase(genType)) {
            return constVal;
        }
        if ("sparse".equalsIgnoreCase(genType) || "sparse-v1".equalsIgnoreCase(genType)) {
            long h = ((seed ^ ((long) row * 374761393L + (long) col * 668265263L))) & 0xFFFFFFFFL;
            h = ((h ^ (h >>> 16)) * 2246822519L) & 0xFFFFFFFFL;
            if ((h % 100) < 90) return 0.0; // 90% sparse
        }
        long h = ((seed ^ ((long) row * 374761393L + (long) col * 668265263L))) & 0xFFFFFFFFL;
        h = ((h ^ (h >>> 16)) * 2246822519L) & 0xFFFFFFFFL;
        h = ((h ^ (h >>> 13)) * 3266489917L) & 0xFFFFFFFFL;
        h = (h ^ (h >>> 16)) & 0xFFFFFFFFL;
        double range = max - min;
        if (range <= 0) range = 1.0;
        return min + (h % (long) (range + 1));
    }

    private void handleStarter(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        Map<String, Object> body = parseBody(exchange);
        String expr1 = (String) body.getOrDefault("expr1", "1+2");
        String expr2 = (String) body.getOrDefault("expr2", "3*2");
        String topOp = (String) body.getOrDefault("topOp", "+");

        Double result = masterServer.runArithmeticStarter(expr1, expr2, topOp);
        if (result == null) {
            sendResponse(exchange, 500, Map.of("error", "Starter arithmetic computation failed"));
            return;
        }

        sendResponse(exchange, 200, Map.of(
                "success", true,
                "expr1", expr1,
                "expr2", expr2,
                "topOp", topOp,
                "result", result
        ));
    }

    @SuppressWarnings("unchecked")
    private void handleComputeAsync(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        try {
            Map<String, Object> body = parseBody(exchange);
            String op = ((String) body.getOrDefault("operation", "+")).trim();
            int rowsA = ((Number) body.getOrDefault("rowsA", 3)).intValue();
            int colsA = ((Number) body.getOrDefault("colsA", 3)).intValue();
            int rowsB = ((Number) body.getOrDefault("rowsB", 3)).intValue();
            int colsB = ((Number) body.getOrDefault("colsB", 3)).intValue();
            Number scalarNum = (Number) body.get("scalar");
            double scalar = scalarNum != null ? scalarNum.doubleValue() : 2.0;
            int taskCount = ((Number) body.getOrDefault("taskCount", 0)).intValue();

            Map<String, Object> genA = (Map<String, Object>) body.getOrDefault("genA", Collections.emptyMap());
            Map<String, Object> genB = (Map<String, Object>) body.getOrDefault("genB", Collections.emptyMap());
            String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");

            MasterServer.AsyncJobRecord rec = masterServer.submitAsyncMatrixJob(
                    op, rowsA, colsA, rowsB, colsB, genA, genB, scalar, taskCount,
                    com.pbl4.core.network.JsonUtils.getMapper().convertValue(body.get("matrixA"), double[][].class),
                    com.pbl4.core.network.JsonUtils.getMapper().convertValue(body.get("matrixB"), double[][].class),
                    idempotencyKey);

            sendResponse(exchange, 202, Map.of(
                    "success", true,
                    "jobId", rec.jobId,
                    "title", rec.title,
                    "operation", rec.operation,
                    "totalTasks", rec.totalTasks,
                    "status", rec.status.name(),
                    "message", "Bài toán đã được tiếp nhận: " + rec.totalTasks + " tác vụ, phân phối cho các Worker đã kết nối."
            ));
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, Map.of("error", e.getMessage() == null ? "Dữ liệu ma trận không hợp lệ." : e.getMessage()));
        } catch (Exception e) {
            sendResponse(exchange, 500, Map.of("error", "Lỗi tiếp nhận bài toán bất đồng bộ: " + e.getMessage()));
        }
    }

    private void handleJobProgress(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        String jobId = null;
        if (query != null && query.contains("jobId=")) {
            String[] pairs = query.split("&");
            for (String p : pairs) {
                if (p.startsWith("jobId=")) {
                    jobId = p.substring(6);
                    break;
                }
            }
        }

        if (jobId == null || jobId.isBlank()) {
            sendResponse(exchange, 400, Map.of("error", "jobId is required in query params (?jobId=...)"));
            return;
        }

        Map<String, Object> progress = masterServer.getJobProgress(jobId);
        if (progress == null) {
            sendResponse(exchange, 404, Map.of("error", "Không tìm thấy Job có ID: " + jobId));
            return;
        }

        sendResponse(exchange, 200, progress);
    }

    private void handleJobCancel(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        Map<String, Object> body = parseBody(exchange);
        String jobId = (String) body.get("jobId");
        if (jobId == null || jobId.isBlank()) {
            sendResponse(exchange, 400, Map.of("error", "jobId is required"));
            return;
        }

        boolean ok = masterServer.cancelAsyncJob(jobId);
        sendResponse(exchange, 200, Map.of("success", ok, "jobId", jobId, "cancelled", ok));
    }

    private void handleScale50(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        sendResponse(exchange, 410, Map.of("error", "Automatic local Worker creation was removed. Start registered laptop Workers with scripts/start-lan-workers.ps1."));
    }
    private void handleFailoverTest(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        String victim = masterServer.simulateFailoverWorker();
        if (victim == null) {
            sendResponse(exchange, 400, Map.of("error", "Không có worker khả dụng để ngắt kết nối!"));
            return;
        }

        sendResponse(exchange, 200, Map.of(
                "success", true,
                "victimWorkerId", victim,
                "message", "Đã ngắt kết nối Worker [" + victim + "]! Hàng đợi Master sẽ tự động failover sang Worker rảnh tiếp theo."
        ));
    }

    @SuppressWarnings("unchecked")
    private void handleWorkersLimit(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        try {
            Map<String, Object> body = parseBody(exchange);
            Number countNum = (Number) body.get("count");
            int count = countNum != null ? countNum.intValue() : 0;

            List<String> activeIds = masterServer.setActiveWorkerLimit(count);
            List<WorkerInfo> allOnline = masterServer.getRegistry().getAllWorkers().stream()
                    .filter(w -> w.getStatus() != WorkerStatus.OFFLINE)
                    .toList();

            sendResponse(exchange, 200, Map.of(
                    "success", true,
                    "requestedCount", count,
                    "activeCount", activeIds.size(),
                    "activeWorkerIds", activeIds,
                    "totalOnline", allOnline.size()
            ));
        } catch (Exception e) {
            sendResponse(exchange, 500, Map.of("error", "Lỗi cấu hình số lượng worker: " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleBenchmarkRun(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        try {
            Map<String, Object> body = parseBody(exchange);
            String problemType = ((String) body.getOrDefault("problemType", "matrix_multiply")).trim().toLowerCase(Locale.ROOT);
            int size = ((Number) body.getOrDefault("size", 100)).intValue();
            int workerCount = ((Number) body.getOrDefault("workerCount", 1)).intValue();
            boolean compareSequential = Boolean.TRUE.equals(body.getOrDefault("compareSequential", true));

            if (size <= 0) {
                sendResponse(exchange, 400, Map.of("error", "Kích thước bài toán (size) phải lớn hơn 0"));
                return;
            }

            List<WorkerInfo> onlineWorkers = masterServer.getRegistry().getAllWorkers().stream()
                    .filter(w -> w.getStatus() != WorkerStatus.OFFLINE)
                    .toList();
            if (onlineWorkers.isEmpty()) {
                sendResponse(exchange, 400, Map.of("error", "Chưa có Worker nào kết nối tới Master. Vui lòng bật ít nhất 1 Worker!"));
                return;
            }

            // Cau hinh so luong Worker duoc phep tham gia
            List<String> activeWorkerIds;
            if (workerCount > 0) {
                activeWorkerIds = masterServer.setActiveWorkerLimit(workerCount);
            } else {
                activeWorkerIds = masterServer.setActiveWorkerLimit(0); // Toan bo worker
            }

            if (activeWorkerIds.isEmpty()) {
                sendResponse(exchange, 400, Map.of("error", "Không có Worker nào sẵn sàng nhận tác vụ!"));
                return;
            }

            Double seqTimeMs = null;
            double distTimeMs;
            boolean computeOk = false;

            if (problemType.equals("graph_dijkstra")) {
                if (size > 800) {
                    sendResponse(exchange, 400, Map.of("error", "Bài toán đồ thị hỗ trợ tối đa 800 đỉnh để đảm bảo hiệu năng!"));
                    return;
                }
                Graph g = new Graph(size);
                Random rng = new Random(42L);
                for (int i = 0; i < size; i++) {
                    for (int j = 0; j < size; j++) {
                        if (i != j && rng.nextDouble() < 0.35) {
                            double weight = Math.round((1.0 + rng.nextDouble() * 19.0) * 10.0) / 10.0;
                            g.addEdge(i, j, weight);
                        }
                    }
                }

                if (compareSequential) {
                    long seqStart = System.nanoTime();
                    ShortestPathAlgorithms.allPairsDijkstraSequential(g);
                    seqTimeMs = (System.nanoTime() - seqStart) / 1_000_000.0;
                }

                long distStart = System.nanoTime();
                double[][] res = masterServer.runGraphShortestPath(g);
                distTimeMs = (System.nanoTime() - distStart) / 1_000_000.0;
                computeOk = (res != null);

            } else {
                // Bai toan ma tran
                if ((long) size * size > 1_000_000) {
                    sendResponse(exchange, 400, Map.of("error", "Kích thước ma trận tối đa 1.000.000 phần tử (1000 x 1000)."));
                    return;
                }

                Matrix A = new Matrix(size, size);
                Matrix B = null;
                boolean isBinary = problemType.equals("matrix_multiply") || problemType.equals("matrix_add");
                if (isBinary) {
                    B = new Matrix(size, size);
                }

                for (int r = 0; r < size; r++) {
                    for (int c = 0; c < size; c++) {
                        A.set(r, c, generateCellValue("random-v1", 42L, r, c, 1.0, 10.0, 0.0));
                        if (isBinary) {
                            B.set(r, c, generateCellValue("random-v1", 99L, r, c, 1.0, 10.0, 0.0));
                        }
                    }
                }

                // Neu la phep nghich dao ma tran hoac dinh thuc: tang duong cheo chinh de luon luon kha nghich (strictly diagonally dominant)
                if (problemType.equals("matrix_inverse") || problemType.equals("matrix_det")) {
                    for (int i = 0; i < size; i++) {
                        A.set(i, i, A.get(i, i) + size * 10.0);
                    }
                }

                // Đo thoi gian tuan tu neu can
                if (compareSequential) {
                    long seqStart = System.nanoTime();
                    if (problemType.equals("matrix_multiply")) {
                        MatrixOperations.multiply(A, B);
                    } else if (problemType.equals("matrix_add")) {
                        MatrixOperations.add(A, B);
                    } else if (problemType.equals("matrix_inverse")) {
                        MatrixOperations.inverse(A);
                    } else if (problemType.equals("matrix_transpose")) {
                        MatrixOperations.transpose(A);
                    } else if (problemType.equals("matrix_det")) {
                        MatrixOperations.determinant(A);
                    } else if (problemType.equals("matrix_rank")) {
                        MatrixOperations.rank(A);
                    }
                    seqTimeMs = (System.nanoTime() - seqStart) / 1_000_000.0;
                }

                // Đo thoi gian phan tan
                long distStart = System.nanoTime();
                if (problemType.equals("matrix_multiply")) {
                    computeOk = (masterServer.runMatrixMultiplication(A, B) != null);
                } else if (problemType.equals("matrix_add")) {
                    computeOk = (masterServer.runMatrixAddOrSub(A, B, true) != null);
                } else if (problemType.equals("matrix_inverse")) {
                    computeOk = (masterServer.runMatrixInverse(A) != null);
                } else if (problemType.equals("matrix_transpose")) {
                    computeOk = (masterServer.runMatrixTranspose(A) != null);
                } else if (problemType.equals("matrix_det")) {
                    computeOk = (masterServer.runMatrixDeterminant(A) != null);
                } else if (problemType.equals("matrix_rank")) {
                    computeOk = (masterServer.runMatrixRank(A) != null);
                } else {
                    sendResponse(exchange, 400, Map.of("error", "Loại bài toán không được hỗ trợ: " + problemType));
                    return;
                }
                distTimeMs = (System.nanoTime() - distStart) / 1_000_000.0;
            }

            if (!computeOk) {
                sendResponse(exchange, 500, Map.of("error", "Tính toán phân tán thất bại hoặc bị timeout!"));
                return;
            }

            double speedup = (seqTimeMs != null && seqTimeMs > 0 && distTimeMs > 0) ? (seqTimeMs / distTimeMs) : 1.0;
            int effectiveWorkers = Math.max(1, activeWorkerIds.size());
            double efficiency = (speedup / effectiveWorkers) * 100.0;

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("problemType", problemType);
            resp.put("size", size);
            resp.put("workerCount", effectiveWorkers);
            resp.put("activeWorkerIds", activeWorkerIds);
            resp.put("distributedTimeMs", Math.round(distTimeMs * 100.0) / 100.0);
            resp.put("sequentialTimeMs", seqTimeMs != null ? Math.round(seqTimeMs * 100.0) / 100.0 : null);
            resp.put("speedup", Math.round(speedup * 1000.0) / 1000.0);
            resp.put("efficiency", Math.round(efficiency * 10.0) / 10.0);
            resp.put("timestamp", System.currentTimeMillis());

            sendResponse(exchange, 200, resp);

        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, Map.of("error", e.getMessage() == null ? "Yêu cầu không hợp lệ" : e.getMessage()));
        } catch (Exception e) {
            sendResponse(exchange, 500, Map.of("error", "Lỗi Benchmark: " + e.getMessage()));
        }
    }

    private Matrix toMatrix(List<List<Number>> raw) {
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("Ma trận không được rỗng.");
        int r = raw.size();
        if (raw.get(0) == null || raw.get(0).isEmpty()) throw new IllegalArgumentException("Ma trận phải có ít nhất một cột.");
        int c = raw.get(0).size();
        if ((long) r * c > 1_000_000) throw new IllegalArgumentException("Mỗi ma trận hỗ trợ tối đa 1.000.000 phần tử.");
        Matrix m = new Matrix(r, c);
        for (int i = 0; i < r; i++) {
            if (raw.get(i) == null || raw.get(i).size() != c) throw new IllegalArgumentException("Các dòng ma trận phải có cùng số cột.");
            for (int j = 0; j < c; j++) {
                Number value = raw.get(i).get(j);
                if (value == null || !Double.isFinite(value.doubleValue())) throw new IllegalArgumentException("Phần tử ma trận phải là số hữu hạn.");
                m.set(i, j, value.doubleValue());
            }
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_HTTP_BODY_BYTES + 1);
            if (bytes.length > MAX_HTTP_BODY_BYTES) throw new IllegalArgumentException("Request vượt quá giới hạn 32 MiB.");
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.isBlank()) return Collections.emptyMap();
            return JsonUtils.fromJson(text, Map.class);
        }
    }

    private boolean handleCors(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, Idempotency-Key");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    private void sendResponse(HttpExchange exchange, int status, Object data) throws IOException {
        handleCors(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        String json = JsonUtils.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
