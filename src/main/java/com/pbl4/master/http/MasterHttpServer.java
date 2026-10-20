package com.pbl4.master.http;

import com.pbl4.computing.matrix.Matrix;
import com.pbl4.computing.matrix.MatrixOperations;
import com.pbl4.core.model.LoadBalancingPolicy;
import com.pbl4.core.model.WorkerInfo;
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
    private final MasterServer masterServer;
    private final int port;
    private HttpServer server;

    public MasterHttpServer(MasterServer masterServer, int port) {
        this.masterServer = masterServer;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        // Dang ky cac endpoint API
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/workers", this::handleWorkers);
        server.createContext("/api/workers/toggle", this::handleToggleWorker);
        server.createContext("/api/policy", this::handlePolicy);
        server.createContext("/api/compute", this::handleCompute);
        server.createContext("/api/starter", this::handleStarter);

        server.start();
        System.out.printf("[MasterHttpServer] REST API Server san sang tai: http://localhost:%d/api/%n", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            System.out.println("[MasterHttpServer] Da dung HTTP Server an toan.");
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        List<WorkerInfo> allWorkers = masterServer.getRegistry().getAllWorkers();
        List<WorkerInfo> available = masterServer.getRegistry().getAvailableWorkers();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("running", masterServer.isRunning());
        resp.put("httpPort", port);
        resp.put("policy", masterServer.getScheduler().getPolicy().name());
        resp.put("totalWorkers", allWorkers.size());
        resp.put("availableWorkers", available.size());

        sendResponse(exchange, 200, resp);
    }

    private void handleWorkers(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        List<WorkerInfo> workers = masterServer.getRegistry().getAllWorkers();
        workers.sort(Comparator.comparing(WorkerInfo::getWorkerId));

        List<Map<String, Object>> list = new ArrayList<>();
        for (WorkerInfo w : workers) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("workerId", w.getWorkerId());
            item.put("host", w.getHost());
            item.put("port", w.getPort());
            item.put("capacity", w.getCapacity());
            item.put("activeTasks", w.getActiveTasks());
            item.put("completedTasks", w.getCompletedTasks());
            item.put("status", w.getStatus().name());
            item.put("paused", w.paused);
            item.put("admissionReason", w.admissionReason != null ? w.admissionReason : (w.paused ? "PAUSED" : "READY"));
            item.put("metrics", w.metrics);
            item.put("lastHeartbeatTime", w.getLastHeartbeatTime());
            list.add(item);
        }

        sendResponse(exchange, 200, list);
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
            String op = ((String) body.getOrDefault("operation", "+")).trim();
            List<List<Number>> rawA = (List<List<Number>>) body.get("matrixA");
            List<List<Number>> rawB = (List<List<Number>>) body.get("matrixB");
            boolean compare = Boolean.TRUE.equals(body.get("compareSequential"));
            Number scalarNum = (Number) body.get("scalar");
            double scalar = scalarNum != null ? scalarNum.doubleValue() : 2.0;

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

        } catch (Exception e) {
            sendResponse(exchange, 500, Map.of("error", "Error executing computation: " + e.getMessage()));
        }
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

    private Matrix toMatrix(List<List<Number>> raw) {
        int r = raw.size();
        int c = raw.get(0).size();
        Matrix m = new Matrix(r, c);
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < c; j++) {
                m.set(i, j, raw.get(i).get(j).doubleValue());
            }
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) return Collections.emptyMap();
            return JsonUtils.fromJson(text, Map.class);
        }
    }

    private boolean handleCors(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
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
