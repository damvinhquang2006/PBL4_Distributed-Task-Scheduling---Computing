package com.pbl4.worker.executor;

import com.pbl4.computing.graph.Graph;
import com.pbl4.computing.graph.ShortestPathAlgorithms;
import com.pbl4.computing.matrix.Matrix;
import com.pbl4.computing.matrix.MatrixOperations;
import com.pbl4.core.model.Task;
import com.pbl4.core.model.TaskResult;
import com.pbl4.core.network.JsonUtils;

import java.util.List;

/**
 * Bo phan giai ma va thuc thi tac vu cu the tren tung Worker Node.
 * Hien thi chi tiet tien trinh xu ly tung buoc mot cho den khi hoan thanh.
 */
public class TaskExecutor {
    public static final ThreadLocal<Boolean> DEMO_DELAY = ThreadLocal.withInitial(() -> false);

    public static TaskResult execute(String workerId, Task task) {
        long start = System.currentTimeMillis();
        try {
            TaskResult result = TaskResult.success(task.getTaskId(), task.getJobId(), workerId);

            switch (task.getTaskType()) {
                case ARITHMETIC -> executeArithmetic(workerId, task, result);
                case MATRIX_ADD -> executeMatrixAdd(workerId, task, result);
                case MATRIX_SUB -> executeMatrixSub(workerId, task, result);
                case MATRIX_MOD -> executeMatrixMod(workerId, task, result);
                case MATRIX_MULTIPLY -> executeMatrixMultiply(workerId, task, result);
                case MATRIX_DETERMINANT -> executeMatrixDeterminant(workerId, task, result);
                case MATRIX_RANK -> executeMatrixRank(workerId, task, result);
                case GRAPH_SHORTEST_PATH -> executeGraphShortestPath(workerId, task, result);
                default -> throw new UnsupportedOperationException("Unknown TaskType: " + task.getTaskType());
            }

            result.setExecutionTimeMs(System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            TaskResult failed = TaskResult.failure(task.getTaskId(), task.getJobId(), workerId, e.getMessage());
            failed.setExecutionTimeMs(System.currentTimeMillis() - start);
            return failed;
        }
    }

    /**
     * Thuc thi tinh toan bieu thuc so hoc (VD: "1+2" -> 3.0, "3*2" -> 6.0)
     */
    private static void executeArithmetic(String workerId, Task task, TaskResult result) {
        String expr = (String) task.getPayloadValue("expression");
        if (expr == null) {
            throw new IllegalArgumentException("Missing 'expression' in arithmetic task payload!");
        }

        System.out.printf("[%s]  |-- [Buoc 1/4] Kiem tra bieu thuc dau vao: '%s'... OK%n", workerId, expr);
        sleepPacing(40);

        System.out.printf("[%s]  |-- [Buoc 2/4] Phan tich cu phap toan hoc va trich xuat toan tu... OK%n", workerId);
        sleepPacing(40);

        double val = evaluateSimpleExpression(expr.trim());
        System.out.printf("[%s]  |-- [Buoc 3/4] Tinh toan phep toan: %s = %.2f... OK%n", workerId, expr, val);
        sleepPacing(40);

        result.addResult("expression", expr);
        result.addResult("value", val);
        System.out.printf("[%s]  |-- [Buoc 4/4] Dong goi TaskResult san sang gui ve Master... OK%n", workerId);
    }

    /**
     * Bo parser don gian ho tro cac phep toan +, -, *, /
     */
    public static double evaluateSimpleExpression(String expr) {
        expr = expr.replaceAll("\\s+", "");
        if (expr.contains("+")) {
            String[] parts = expr.split("\\+");
            return Double.parseDouble(parts[0]) + Double.parseDouble(parts[1]);
        } else if (expr.contains("*")) {
            String[] parts = expr.split("\\*");
            return Double.parseDouble(parts[0]) * Double.parseDouble(parts[1]);
        } else if (expr.contains("/")) {
            String[] parts = expr.split("/");
            return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
        } else if (expr.contains("-") && !expr.startsWith("-")) {
            String[] parts = expr.split("-");
            return Double.parseDouble(parts[0]) - Double.parseDouble(parts[1]);
        }
        return Double.parseDouble(expr);
    }

    /**
     * Thuc thi phep cong lat cat dong: C_slice = A_slice + B_slice
     */
    private static void executeMatrixAdd(String workerId, Task task, TaskResult result) {
        int startRow = ((Number) task.getPayloadValue("startRow")).intValue();
        double[][] sliceA = parse2DArray(task.getPayloadValue("sliceA"));
        double[][] sliceB = parse2DArray(task.getPayloadValue("sliceB"));
        int rows = sliceA.length;

        System.out.printf("[%s]  |-- [Buoc 1/4] Giai ma du lieu lat cat (startRow=%d, so dong=%d)... OK%n", workerId, startRow, rows);
        sleepPacing(30);

        System.out.printf("[%s]  |-- [Buoc 2/4] Bat dau cong tung phan tu ma tran theo dong:%n", workerId);
        double[][] sliceC = new double[rows][sliceA[0].length];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < sliceA[0].length; j++) {
                sliceC[i][j] = sliceA[i][j] + sliceB[i][j];
            }
            int percent = (int) Math.round(((i + 1.0) / rows) * 100);
            if (rows <= 8 || i % Math.max(1, rows / 4) == 0 || i == rows - 1) {
                System.out.printf("[%s]  |    |-- Tien do: %s %3d%% (Dong %d/%d hoan tat)%n",
                        workerId, getProgressBar(percent), percent, (i + 1), rows);
                sleepPacing(25);
            }
        }

        System.out.printf("[%s]  |-- [Buoc 3/4] Tinh toan xong toan bo %d dong cua lat cat... OK%n", workerId, rows);
        result.addResult("startRow", startRow);
        result.addResult("rowCount", sliceC.length);
        result.addResult("sliceResult", sliceC);

        System.out.printf("[%s]  |-- [Buoc 4/4] Dong goi lat cat ket qua vao TaskResult... OK%n", workerId);
    }

    /**
     * Thuc thi phep tru lat cat dong: C_slice = A_slice - B_slice
     */
    private static void executeMatrixSub(String workerId, Task task, TaskResult result) {
        int startRow = ((Number) task.getPayloadValue("startRow")).intValue();
        double[][] sliceA = parse2DArray(task.getPayloadValue("sliceA"));
        double[][] sliceB = parse2DArray(task.getPayloadValue("sliceB"));
        int rows = sliceA.length;

        System.out.printf("[%s]  |-- [Buoc 1/4] Giai ma du lieu lat cat (startRow=%d, so dong=%d)... OK%n", workerId, startRow, rows);
        sleepPacing(30);

        System.out.printf("[%s]  |-- [Buoc 2/4] Bat dau tru tung phan tu ma tran theo dong:%n", workerId);
        double[][] sliceC = new double[rows][sliceA[0].length];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < sliceA[0].length; j++) {
                sliceC[i][j] = sliceA[i][j] - sliceB[i][j];
            }
            int percent = (int) Math.round(((i + 1.0) / rows) * 100);
            if (rows <= 8 || i % Math.max(1, rows / 4) == 0 || i == rows - 1) {
                System.out.printf("[%s]  |    |-- Tien do: %s %3d%% (Dong %d/%d hoan tat)%n",
                        workerId, getProgressBar(percent), percent, (i + 1), rows);
                sleepPacing(25);
            }
        }

        System.out.printf("[%s]  |-- [Buoc 3/4] Tinh toan xong toan bo %d dong cua lat cat... OK%n", workerId, rows);
        result.addResult("startRow", startRow);
        result.addResult("rowCount", sliceC.length);
        result.addResult("sliceResult", sliceC);

        System.out.printf("[%s]  |-- [Buoc 4/4] Dong goi lat cat ket qua vao TaskResult... OK%n", workerId);
    }

    /**
     * Thuc thi phep chia lay du (%) lat cat dong: C_slice = A_slice % B_slice
     */
    private static void executeMatrixMod(String workerId, Task task, TaskResult result) {
        int startRow = ((Number) task.getPayloadValue("startRow")).intValue();
        double[][] sliceA = parse2DArray(task.getPayloadValue("sliceA"));
        double[][] sliceB = parse2DArray(task.getPayloadValue("sliceB"));
        int rows = sliceA.length;

        System.out.printf("[%s]  |-- [Buoc 1/4] Giai ma du lieu lat cat modulo (startRow=%d, so dong=%d)... OK%n", workerId, startRow, rows);
        sleepPacing(30);

        System.out.printf("[%s]  |-- [Buoc 2/4] Bat dau chia lay du tung phan tu ma tran theo dong:%n", workerId);
        double[][] sliceC = new double[rows][sliceA[0].length];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < sliceA[0].length; j++) {
                double bVal = sliceB[i][j];
                sliceC[i][j] = Math.abs(bVal) < 1e-9 ? 0.0 : (sliceA[i][j] % bVal);
            }
            int percent = (int) Math.round(((i + 1.0) / rows) * 100);
            if (rows <= 8 || i % Math.max(1, rows / 4) == 0 || i == rows - 1) {
                System.out.printf("[%s]  |    |-- Tien do: %s %3d%% (Dong %d/%d hoan tat)%n",
                        workerId, getProgressBar(percent), percent, (i + 1), rows);
                sleepPacing(25);
            }
        }

        System.out.printf("[%s]  |-- [Buoc 3/4] Tinh toan xong toan bo %d dong modulo cua lat cat... OK%n", workerId, rows);
        result.addResult("startRow", startRow);
        result.addResult("rowCount", sliceC.length);
        result.addResult("sliceResult", sliceC);

        System.out.printf("[%s]  |-- [Buoc 4/4] Dong goi lat cat ket qua vao TaskResult... OK%n", workerId);
    }

    /**
     * Thuc thi phep nhan lat cat dong: C_slice = A_slice * B
     */
    private static void executeMatrixMultiply(String workerId, Task task, TaskResult result) {
        int startRow = ((Number) task.getPayloadValue("startRow")).intValue();
        int colsB = ((Number) task.getPayloadValue("colsB")).intValue();
        double[][] sliceA = parse2DArray(task.getPayloadValue("sliceA"));
        double[][] dataB = parse2DArray(task.getPayloadValue("dataB"));
        int sliceRows = sliceA.length;
        int kDim = sliceA[0].length;

        System.out.printf("[%s]  |-- [Buoc 1/5] Nhan lat cat A [%d x %d] (startRow=%d) va ma tran B [%d x %d]... OK%n",
                workerId, sliceRows, kDim, startRow, dataB.length, colsB);
        sleepPacing(30);

        System.out.printf("[%s]  |-- [Buoc 2/5] Khoi tao ma tran dem ket qua [%d x %d]... OK%n",
                workerId, sliceRows, colsB);
        sleepPacing(25);

        System.out.printf("[%s]  |-- [Buoc 3/5] Bat dau tinh tich vo huong tung dong (A_slice x B):%n", workerId);
        double[][] sliceC = new double[sliceRows][colsB];

        for (int i = 0; i < sliceRows; i++) {
            for (int k = 0; k < kDim; k++) {
                double aVal = sliceA[i][k];
                for (int j = 0; j < colsB; j++) {
                    sliceC[i][j] += aVal * dataB[k][j];
                }
            }

            int percent = (int) Math.round(((i + 1.0) / sliceRows) * 100);
            if (sliceRows <= 8 || i % Math.max(1, sliceRows / 4) == 0 || i == sliceRows - 1) {
                System.out.printf("[%s]  |    |-- Tien do: %s %3d%% (Dong %d/%d hoan tat)%n",
                        workerId, getProgressBar(percent), percent, (i + 1), sliceRows);
                sleepPacing(30);
            }
        }

        System.out.printf("[%s]  |-- [Buoc 4/5] Hoan thanh phep nhan toan bo %d dong!... OK%n", workerId, sliceRows);
        result.addResult("startRow", startRow);
        result.addResult("rowCount", sliceC.length);
        result.addResult("sliceResult", sliceC);

        System.out.printf("[%s]  |-- [Buoc 5/5] Dong goi lat cat C_slice [%d x %d] vao TaskResult... OK%n",
                workerId, sliceRows, colsB);
    }

    /**
     * Thuc thi mot phan dinh thuc theo khai trien Laplace tren dong dau tien.
     */
    private static void executeMatrixDeterminant(String workerId, Task task, TaskResult result) {
        double[][] matrixData = parse2DArray(task.getPayloadValue("matrixData"));
        List<?> columnsRaw = (List<?>) task.getPayloadValue("assignedColumns");
        int n = matrixData.length;

        if (n == 0 || matrixData[0].length != n) {
            throw new IllegalArgumentException("Determinant task requires a square matrix!");
        }

        System.out.printf("[%s]  |-- [Buoc 1/4] Giai ma ma tran vuong [%d x %d] va %d cot cofactor... OK%n",
                workerId, n, n, columnsRaw.size());
        sleepPacing(30);

        double partialDet = 0.0;
        System.out.printf("[%s]  |-- [Buoc 2/4] Bat dau tinh cac so hang cofactor duoc giao:%n", workerId);
        for (int i = 0; i < columnsRaw.size(); i++) {
            int col = ((Number) columnsRaw.get(i)).intValue();
            double coefficient = matrixData[0][col];
            double cofactorSign = (col % 2 == 0) ? 1.0 : -1.0;
            double minorDet = (n == 1) ? 1.0 : MatrixOperations.determinant(new Matrix(minor(matrixData, 0, col)));
            double term = cofactorSign * coefficient * minorDet;
            partialDet += term;

            int percent = (int) Math.round(((i + 1.0) / columnsRaw.size()) * 100);
            System.out.printf("[%s]  |    |-- Tien do: %s %3d%% (Cot %d, so hang %.4f)%n",
                    workerId, getProgressBar(percent), percent, col, term);
            sleepPacing(30);
        }

        System.out.printf("[%s]  |-- [Buoc 3/4] Hoan thanh tong rieng dinh thuc: %.6f... OK%n", workerId, partialDet);
        result.addResult("partialDeterminant", partialDet);
        result.addResult("columnCount", columnsRaw.size());

        System.out.printf("[%s]  |-- [Buoc 4/4] Dong goi tong rieng dinh thuc vao TaskResult... OK%n", workerId);
    }

    /**
     * Thuc thi rut gon lat cat dong de tao basis cuc bo cho bai toan hang ma tran.
     */
    private static void executeMatrixRank(String workerId, Task task, TaskResult result) {
        int startRow = ((Number) task.getPayloadValue("startRow")).intValue();
        int cols = ((Number) task.getPayloadValue("cols")).intValue();
        double[][] sliceA = parse2DArray(task.getPayloadValue("sliceA"));

        System.out.printf("[%s]  |-- [Buoc 1/4] Giai ma lat cat A (startRow=%d, so dong=%d, so cot=%d)... OK%n",
                workerId, startRow, sliceA.length, cols);
        sleepPacing(30);

        System.out.printf("[%s]  |-- [Buoc 2/4] Rut gon lat cat dong thanh basis cuc bo...%n", workerId);
        double[][] localBasis = MatrixOperations.rowBasis(sliceA, cols);
        int percent = 100;
        System.out.printf("[%s]  |    |-- Tien do: %s %3d%% (Basis cuc bo co %d dong doc lap)%n",
                workerId, getProgressBar(percent), percent, localBasis.length);
        sleepPacing(30);

        System.out.printf("[%s]  |-- [Buoc 3/4] Hoan tat rut gon hang cuc bo... OK%n", workerId);
        result.addResult("startRow", startRow);
        result.addResult("basisRows", localBasis);
        result.addResult("localRank", localBasis.length);

        System.out.printf("[%s]  |-- [Buoc 4/4] Dong goi basis cuc bo vao TaskResult... OK%n", workerId);
    }

    /**
     * Thuc thi tim duong di ngan nhat do thi cho danh sach cac dinh nguon duoc giao.
     */
    private static void executeGraphShortestPath(String workerId, Task task, TaskResult result) {
        Graph graph = JsonUtils.getMapper().convertValue(task.getPayloadValue("graph"), Graph.class);
        List<?> sourcesRaw = (List<?>) task.getPayloadValue("sourceVertices");
        int[] sourceVertices = new int[sourcesRaw.size()];
        for (int i = 0; i < sourcesRaw.size(); i++) {
            sourceVertices[i] = ((Number) sourcesRaw.get(i)).intValue();
        }

        System.out.printf("[%s]  |-- [Buoc 1/4] Giai ma cau truc do thi (%d dinh, %d dinh nguon)... OK%n",
                workerId, graph.getVertices(), sourceVertices.length);
        sleepPacing(30);

        System.out.printf("[%s]  |-- [Buoc 2/4] Chay thuat toan Dijkstra cho tung dinh nguon:%n", workerId);
        double[][] distances = new double[sourceVertices.length][graph.getVertices()];

        for (int i = 0; i < sourceVertices.length; i++) {
            int src = sourceVertices[i];
            distances[i] = ShortestPathAlgorithms.dijkstra(graph, src);

            int percent = (int) Math.round(((i + 1.0) / sourceVertices.length) * 100);
            System.out.printf("[%s]  |    |-- Tien do: %s %3d%% (Hoan thanh Dijkstra dinh %d)%n",
                    workerId, getProgressBar(percent), percent, src);
            sleepPacing(30);
        }

        System.out.printf("[%s]  |-- [Buoc 3/4] Hoan tat tinh toan khoang cach cho tat ca dinh nguon... OK%n", workerId);
        result.addResult("sourceVertices", sourceVertices);
        result.addResult("distances", distances);

        System.out.printf("[%s]  |-- [Buoc 4/4] Dong goi ma tran khoang cach cuc bo vao TaskResult... OK%n", workerId);
    }

    /**
     * Tao thanh tien trinh hinh hoc dang chuoi ASCII: [====------]
     */
    private static String getProgressBar(int percent) {
        int totalBars = 10;
        int filledBars = (percent * totalBars) / 100;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < totalBars; i++) {
            if (i < filledBars) {
                sb.append("=");
            } else {
                sb.append("-");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static void sleepPacing(long ms) {
        if (!DEMO_DELAY.get()) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private static double[][] parse2DArray(Object obj) {
        if (obj instanceof double[][]) {
            return (double[][]) obj;
        }
        return JsonUtils.getMapper().convertValue(obj, double[][].class);
    }

    private static double[][] minor(double[][] matrix, int excludedRow, int excludedCol) {
        int n = matrix.length;
        double[][] result = new double[n - 1][n - 1];
        int r = 0;
        for (int i = 0; i < n; i++) {
            if (i == excludedRow) {
                continue;
            }
            int c = 0;
            for (int j = 0; j < n; j++) {
                if (j == excludedCol) {
                    continue;
                }
                result[r][c++] = matrix[i][j];
            }
            r++;
        }
        return result;
    }
}
