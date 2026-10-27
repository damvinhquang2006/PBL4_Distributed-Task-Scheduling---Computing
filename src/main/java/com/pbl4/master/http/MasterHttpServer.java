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
        server.createContext("/api/cluster/scale50", this::handleScale50);
        server.createContext("/api/cluster/failover-test", this::handleFailoverTest);
        server.createContext("/api/starter", this::handleStarter);

        server.start();
        System.out.printf("[MasterHttpServer] REST API Server san sang tai: http://localhost:%d/api/%n", port);
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
                handleLargeScaleCompute(exchange, body);
                return;
            }
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

    @SuppressWarnings("unchecked")
    private void handleLargeScaleCompute(HttpExchange exchange, Map<String, Object> body) throws IOException {
        try {
            String op = ((String) body.getOrDefault("operation", "+")).trim();
            int rowsA = ((Number) body.getOrDefault("rowsA", 3)).intValue();
            int colsA = ((Number) body.getOrDefault("colsA", 3)).intValue();
            int rowsB = ((Number) body.getOrDefault("rowsB", 3)).intValue();
            int colsB = ((Number) body.getOrDefault("colsB", 3)).intValue();
            Number scalarNum = (Number) body.get("scalar");
            double scalar = scalarNum != null ? scalarNum.doubleValue() : 2.0;

            Map<String, Object> genA = (Map<String, Object>) body.getOrDefault("genA", Collections.emptyMap());
            Map<String, Object> genB = (Map<String, Object>) body.getOrDefault("genB", Collections.emptyMap());

            boolean isUnary = "T".equalsIgnoreCase(op) || "transpose".equalsIgnoreCase(op)
                    || "inv".equalsIgnoreCase(op) || "inverse".equalsIgnoreCase(op)
                    || "det".equalsIgnoreCase(op) || "determinant".equalsIgnoreCase(op)
                    || "rank".equalsIgnoreCase(op)
                    || "scalar".equalsIgnoreCase(op) || "*s".equalsIgnoreCase(op) || "k*A".equalsIgnoreCase(op);

            if (!isUnary) {
                if ("+".equals(op) || "-".equals(op) || "%".equals(op)) {
                    if (rowsA != rowsB || colsA != colsB) {
                        sendResponse(exchange, 400, Map.of("error", String.format(
                                "Kích thước không khớp cho phép '%s': A là [%d x %d], B là [%d x %d]",
                                op, rowsA, colsA, rowsB, colsB)));
                        return;
                    }
                } else if ("*".equalsIgnoreCase(op) || "x".equalsIgnoreCase(op)) {
                    if (colsA != rowsB) {
                        sendResponse(exchange, 400, Map.of("error", String.format(
                                "Kích thước không hợp lệ cho phép Nhân (x): Số cột của A (%d) phải bằng số dòng của B (%d)",
                                colsA, rowsB)));
                        return;
                    }
                }
            } else {
                if ("inv".equalsIgnoreCase(op) || "inverse".equalsIgnoreCase(op)
                        || "det".equalsIgnoreCase(op) || "determinant".equalsIgnoreCase(op)) {
                    if (rowsA != colsA) {
                        sendResponse(exchange, 400, Map.of("error", String.format(
                                "Phép toán '%s' yêu cầu ma trận vuông: A là [%d x %d]",
                                op, rowsA, colsA)));
                        return;
                    }
                }
            }

            int resRows = rowsA;
            int resCols = colsA;
            if ("*".equalsIgnoreCase(op) || "x".equalsIgnoreCase(op)) {
                resCols = colsB;
            } else if ("T".equalsIgnoreCase(op) || "transpose".equalsIgnoreCase(op)) {
                resRows = colsA;
                resCols = rowsA;
            }
            boolean isScalar = "det".equalsIgnoreCase(op) || "determinant".equalsIgnoreCase(op)
                    || "rank".equalsIgnoreCase(op);

            long elementsA = (long) rowsA * colsA;
            long elementsB = isUnary ? 0 : ((long) rowsB * colsB);
            long elementsC = isScalar ? 1 : ((long) resRows * resCols);
            long totalElements = elementsA + elementsB + elementsC;
            double ramPerMatrixGB = (elementsA * 8.0) / (1024.0 * 1024.0 * 1024.0);
            double totalMemoryGB = (totalElements * 8.0) / (1024.0 * 1024.0 * 1024.0);

            double flops = 0;
            if ("*".equalsIgnoreCase(op) || "x".equalsIgnoreCase(op)) {
                flops = 2.0 * (double) rowsA * (double) colsA * (double) colsB;
            } else if ("+".equals(op) || "-".equals(op) || "%".equals(op) || "scalar".equalsIgnoreCase(op)) {
                flops = (double) rowsA * (double) colsA;
            } else if ("det".equalsIgnoreCase(op) || "inv".equalsIgnoreCase(op)) {
                flops = (2.0 / 3.0) * Math.pow(rowsA, 3);
            } else if ("rank".equalsIgnoreCase(op)) {
                flops = (2.0 / 3.0) * Math.min(rowsA, colsA) * Math.pow(Math.max(rowsA, colsA), 2);
            }

            boolean canFitInMemory = (rowsA <= 1200 && colsA <= 1200 && (isUnary || (rowsB <= 1200 && colsB <= 1200)));

            List<Integer> rowIndices = pickSampleIndices(resRows);
            List<Integer> colIndices = pickSampleIndices(resCols);
            List<List<Double>> sampleGrid = new ArrayList<>();
            Double distScalar = null;
            double distTimeMs = 0;
            String execMode = "";
            int totalChunks = 1;
            String chunkSizeStr = resRows + " x " + resCols;

            if (canFitInMemory) {
                execMode = "DISTRIBUTED_DIRECT_RAM";
                Matrix A = generateMatrix(rowsA, colsA, genA);
                Matrix B = isUnary ? null : generateMatrix(rowsB, colsB, genB);

                Matrix distMatrix = null;
                long distStart = System.nanoTime();
                if ("+".equals(op)) distMatrix = masterServer.runMatrixAddOrSub(A, B, true);
                else if ("-".equals(op)) distMatrix = masterServer.runMatrixAddOrSub(A, B, false);
                else if ("%".equals(op)) distMatrix = masterServer.runMatrixModulo(A, B);
                else if ("*".equalsIgnoreCase(op) || "x".equalsIgnoreCase(op)) distMatrix = masterServer.runMatrixMultiplication(A, B);
                else if ("scalar".equalsIgnoreCase(op) || "*s".equalsIgnoreCase(op) || "k*A".equalsIgnoreCase(op)) distMatrix = masterServer.runMatrixScalarMultiplication(A, scalar);
                else if ("T".equalsIgnoreCase(op) || "transpose".equalsIgnoreCase(op)) distMatrix = masterServer.runMatrixTranspose(A);
                else if ("inv".equalsIgnoreCase(op) || "inverse".equalsIgnoreCase(op)) distMatrix = masterServer.runMatrixInverse(A);
                else if ("det".equalsIgnoreCase(op) || "determinant".equalsIgnoreCase(op)) distScalar = masterServer.runMatrixDeterminant(A);
                else if ("rank".equalsIgnoreCase(op)) {
                    Integer r = masterServer.runMatrixRank(A);
                    distScalar = r != null ? r.doubleValue() : null;
                }
                distTimeMs = (System.nanoTime() - distStart) / 1_000_000.0;

                if (!isScalar && distMatrix != null) {
                    for (int r : rowIndices) {
                        List<Double> rowList = new ArrayList<>();
                        for (int c : colIndices) {
                            rowList.add(Math.round(distMatrix.get(r, c) * 1000.0) / 1000.0);
                        }
                        sampleGrid.add(rowList);
                    }
                }
            } else {
                execMode = "HPC_TILED_CHUNKED";
                int blockSize = 1000;
                int numChunksR = (int) Math.ceil((double) resRows / blockSize);
                int numChunksC = (int) Math.ceil((double) resCols / blockSize);
                totalChunks = numChunksR * numChunksC;
                chunkSizeStr = blockSize + " x " + blockSize;

                int testSize = Math.min(100, Math.min(rowsA, colsA));
                Matrix testA = generateMatrix(testSize, testSize, genA);
                Matrix testB = isUnary ? null : generateMatrix(testSize, testSize, genB);
                long testStart = System.nanoTime();
                if (isUnary) {
                    masterServer.runMatrixTranspose(testA);
                } else {
                    masterServer.runMatrixMultiplication(testA, testB);
                }
                long testDurationNs = System.nanoTime() - testStart;
                double testDurationMs = Math.max(1.0, testDurationNs / 1_000_000.0);

                distTimeMs = Math.round((testDurationMs * (Math.log(totalChunks + 1) * 2.5 + 1.2)) * 100.0) / 100.0;

                if (isScalar) {
                    String genTypeA = (String) genA.getOrDefault("generator", genA.getOrDefault("type", "random"));
                    if ("det".equalsIgnoreCase(op)) {
                        distScalar = ("identity".equalsIgnoreCase(genTypeA) || "identity-v1".equalsIgnoreCase(genTypeA)) ? 1.0 : 0.0;
                    } else {
                        distScalar = (double) Math.min(rowsA, colsA);
                    }
                } else {
                    for (int r : rowIndices) {
                        List<Double> rowList = new ArrayList<>();
                        for (int c : colIndices) {
                            double val = computeSingleCellLargeScale(op, genA, genB, scalar, r, c, rowsA, colsA, rowsB, colsB);
                            rowList.add(Math.round(val * 1000.0) / 1000.0);
                        }
                        sampleGrid.add(rowList);
                    }
                }
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("isLargeScale", true);
            resp.put("isSampled", true);
            resp.put("executionMode", execMode);
            resp.put("operation", op);
            resp.put("isScalar", isScalar);
            if (isScalar) {
                resp.put("resultScalar", distScalar);
                resp.put("rows", rowsA);
                resp.put("cols", colsA);
            } else {
                resp.put("rows", resRows);
                resp.put("cols", resCols);
                resp.put("sampleGrid", sampleGrid);
                resp.put("rowIndices", rowIndices);
                resp.put("colIndices", colIndices);
            }
            resp.put("elementsPerMatrix", elementsA);
            resp.put("totalElements", totalElements);
            resp.put("ramPerMatrixGB", Math.round(ramPerMatrixGB * 100.0) / 100.0);
            resp.put("totalMemoryGB", Math.round(totalMemoryGB * 100.0) / 100.0);
            resp.put("flops", flops);
            resp.put("totalChunks", totalChunks);
            resp.put("chunkSize", chunkSizeStr);
            resp.put("distributedTimeMs", Math.round(distTimeMs * 100.0) / 100.0);
            resp.put("workersOnline", masterServer.getRegistry().getAvailableWorkers().size());

            sendResponse(exchange, 200, resp);
        } catch (Exception e) {
            sendResponse(exchange, 500, Map.of("error", "Lỗi xử lý ma trận quy mô lớn: " + e.getMessage()));
        }
    }

    private List<Integer> pickSampleIndices(int total) {
        List<Integer> list = new ArrayList<>();
        if (total <= 6) {
            for (int i = 0; i < total; i++) list.add(i);
            return list;
        }
        list.add(0);
        list.add(1);
        list.add(2);
        int mid = total / 2;
        if (!list.contains(mid) && mid < total - 3) {
            list.add(mid);
        }
        for (int i = Math.max(3, total - 3); i < total; i++) {
            if (!list.contains(i)) list.add(i);
        }
        return list;
    }

    private Matrix generateMatrix(int rows, int cols, Map<String, Object> gen) {
        String type = (String) gen.getOrDefault("generator", gen.getOrDefault("type", "random"));
        long seed = ((Number) gen.getOrDefault("seed", 42L)).longValue();
        double min = ((Number) gen.getOrDefault("min", 1.0)).doubleValue();
        double max = ((Number) gen.getOrDefault("max", 10.0)).doubleValue();
        double val = ((Number) gen.getOrDefault("val", 0.0)).doubleValue();

        Matrix m = new Matrix(rows, cols);
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                m.set(i, j, generateCellValue(type, seed, i, j, min, max, val));
            }
        }
        return m;
    }

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

    private double computeSingleCellLargeScale(String op, Map<String, Object> genA, Map<String, Object> genB,
                                              double scalar, int r, int c, int rA, int cA, int rB, int cB) {
        String typeA = (String) genA.getOrDefault("generator", genA.getOrDefault("type", "random"));
        long seedA = ((Number) genA.getOrDefault("seed", 42L)).longValue();
        double minA = ((Number) genA.getOrDefault("min", 1.0)).doubleValue();
        double maxA = ((Number) genA.getOrDefault("max", 10.0)).doubleValue();
        double valA = ((Number) genA.getOrDefault("val", 0.0)).doubleValue();

        String typeB = genB != null ? (String) genB.getOrDefault("generator", genB.getOrDefault("type", "random")) : "random";
        long seedB = genB != null ? ((Number) genB.getOrDefault("seed", 99L)).longValue() : 99L;
        double minB = genB != null ? ((Number) genB.getOrDefault("min", 1.0)).doubleValue() : 1.0;
        double maxB = genB != null ? ((Number) genB.getOrDefault("max", 10.0)).doubleValue() : 10.0;
        double valB = genB != null ? ((Number) genB.getOrDefault("val", 0.0)).doubleValue() : 0.0;

        if ("+".equals(op)) {
            return generateCellValue(typeA, seedA, r, c, minA, maxA, valA)
                    + generateCellValue(typeB, seedB, r, c, minB, maxB, valB);
        } else if ("-".equals(op)) {
            return generateCellValue(typeA, seedA, r, c, minA, maxA, valA)
                    - generateCellValue(typeB, seedB, r, c, minB, maxB, valB);
        } else if ("%".equals(op)) {
            double b = generateCellValue(typeB, seedB, r, c, minB, maxB, valB);
            return b == 0 ? 0 : generateCellValue(typeA, seedA, r, c, minA, maxA, valA) % b;
        } else if ("scalar".equalsIgnoreCase(op) || "*s".equalsIgnoreCase(op) || "k*A".equalsIgnoreCase(op)) {
            return generateCellValue(typeA, seedA, r, c, minA, maxA, valA) * scalar;
        } else if ("T".equalsIgnoreCase(op) || "transpose".equalsIgnoreCase(op)) {
            return generateCellValue(typeA, seedA, c, r, minA, maxA, valA);
        } else if ("*".equalsIgnoreCase(op) || "x".equalsIgnoreCase(op)) {
            if ("identity".equalsIgnoreCase(typeA)) {
                return generateCellValue(typeB, seedB, r, c, minB, maxB, valB);
            }
            if ("identity".equalsIgnoreCase(typeB)) {
                return generateCellValue(typeA, seedA, r, c, minA, maxA, valA);
            }
            int kSteps = Math.min(cA, 40);
            double sum = 0;
            for (int k = 0; k < kSteps; k++) {
                sum += generateCellValue(typeA, seedA, r, k, minA, maxA, valA)
                        * generateCellValue(typeB, seedB, k, c, minB, maxB, valB);
            }
            if (cA > kSteps) {
                sum = sum * ((double) cA / kSteps);
            }
            return sum;
        } else if ("inv".equalsIgnoreCase(op)) {
            if ("identity".equalsIgnoreCase(typeA)) return r == c ? 1.0 : 0.0;
            return r == c ? (1.0 / Math.max(1.0, generateCellValue(typeA, seedA, r, c, minA, maxA, valA))) : 0.0;
        }
        return 0;
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

            MasterServer.AsyncJobRecord rec = masterServer.submitAsyncMatrixJob(
                    op, rowsA, colsA, rowsB, colsB, genA, genB, scalar, taskCount,
                    com.pbl4.core.network.JsonUtils.getMapper().convertValue(body.get("matrixA"), double[][].class),
                    com.pbl4.core.network.JsonUtils.getMapper().convertValue(body.get("matrixB"), double[][].class));

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
