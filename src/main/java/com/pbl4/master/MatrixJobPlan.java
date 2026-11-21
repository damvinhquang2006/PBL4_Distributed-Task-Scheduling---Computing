package com.pbl4.master;

import com.pbl4.computing.matrix.Matrix;
import com.pbl4.core.model.*;
import com.pbl4.master.aggregate.ResultAggregator;
import com.pbl4.master.http.MasterHttpServer;
import com.pbl4.master.split.TaskSplitter;
import java.util.*;

/** Uses the same concrete task payloads and aggregation for console and web jobs. */
final class MatrixJobPlan {
    static final long MAX_MATRIX_CELLS = 50_000_000L;
    final String operation;
    final Matrix a, b;
    final double scalar;

    MatrixJobPlan(String operation, int rowsA, int colsA, int rowsB, int colsB,
                  Map<String, Object> genA, Map<String, Object> genB, Double scalar,
                  double[][] inputA, double[][] inputB) {
        this.operation = switch (operation.trim().toLowerCase(Locale.ROOT)) {
            case "+", "-", "%", "x", "scalar", "t", "inv", "det", "rank" -> operation.trim().toLowerCase(Locale.ROOT);
            case "*" -> "x";
            default -> throw new IllegalArgumentException("Phép toán không được hỗ trợ: " + operation);
        };
        validateSize(rowsA, colsA);
        boolean binary = Set.of("+", "-", "%", "x").contains(this.operation);
        if (binary) {
            validateSize(rowsB, colsB);
            if ("x".equals(this.operation)) {
                if (colsA != rowsB) throw new IllegalArgumentException("Nhân ma trận yêu cầu số cột A bằng số dòng B.");
                validateSize(rowsA, colsB);
            } else if (rowsA != rowsB || colsA != colsB) {
                throw new IllegalArgumentException("Hai ma trận phải có cùng kích thước.");
            }
        }
        if (Set.of("inv", "det").contains(this.operation) && rowsA != colsA)
            throw new IllegalArgumentException("Phép toán yêu cầu ma trận vuông.");
        this.scalar = scalar == null ? 2.0 : scalar;
        if (!Double.isFinite(this.scalar)) throw new IllegalArgumentException("Hệ số phải là số hữu hạn.");
        a = matrix(rowsA, colsA, genA, inputA);
        b = binary ? matrix(rowsB, colsB, genB, inputB) : null;
    }

    private static void validateSize(int rows, int cols) {
        if (rows <= 0 || cols <= 0) throw new IllegalArgumentException("Kích thước ma trận phải lớn hơn 0.");
        if ((long) rows * cols > MAX_MATRIX_CELLS)
            throw new IllegalArgumentException("Tính toán đầy đủ hiện hỗ trợ tối đa 50.000.000 phần tử mỗi ma trận; hãy giảm kích thước.");
    }

    long maxCells() {
        long cellsA = (long) a.getRows() * a.getCols();
        long cellsB = b == null ? 0L : (long) b.getRows() * b.getCols();
        long cellsOut = "x".equals(operation) && b != null ? (long) a.getRows() * b.getCols() : cellsA;
        return Math.max(cellsA, Math.max(cellsB, cellsOut));
    }

    private static Matrix matrix(int rows, int cols, Map<String, Object> descriptor, double[][] input) {
        if (input != null) {
            if (input.length != rows) throw new IllegalArgumentException("Số dòng dữ liệu ma trận không khớp kích thước.");
            for (double[] row : input) {
                if (row == null || row.length != cols) throw new IllegalArgumentException("Số cột dữ liệu ma trận không khớp kích thước.");
                for (double value : row) if (!Double.isFinite(value))
                    throw new IllegalArgumentException("Phần tử ma trận phải là số hữu hạn.");
            }
            return new Matrix(input);
        }
        Map<String, Object> gen = descriptor == null ? Map.of() : descriptor;
        String type = (String) gen.getOrDefault("generator", gen.getOrDefault("type", "random"));
        long seed = ((Number) gen.getOrDefault("seed", 42L)).longValue();
        double min = ((Number) gen.getOrDefault("min", 1.0)).doubleValue();
        double max = ((Number) gen.getOrDefault("max", 10.0)).doubleValue();
        double value = ((Number) gen.getOrDefault("val", 0.0)).doubleValue();
        if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(value))
            throw new IllegalArgumentException("Giá trị bộ sinh phải là số hữu hạn.");
        Matrix matrix = new Matrix(rows, cols);
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++)
            matrix.set(r, c, MasterHttpServer.generateCellValue(type, seed, r, c, min, max, value));
        return matrix;
    }

    List<Task> tasks(String id, int requested) {
        return switch (operation) {
            case "+", "-", "%" -> TaskSplitter.splitMatrixAddSub(id, a, b,
                operation.equals("+") ? TaskType.MATRIX_ADD : operation.equals("-") ? TaskType.MATRIX_SUB : TaskType.MATRIX_MOD, requested);
            case "x" -> TaskSplitter.splitMatrixMultiply(id, a, b, requested);
            case "scalar" -> TaskSplitter.splitMatrixScalarMul(id, a, scalar, requested);
            case "t" -> TaskSplitter.splitMatrixTranspose(id, a, requested);
            case "inv" -> TaskSplitter.splitMatrixInverse(id, a, requested);
            case "det" -> TaskSplitter.splitMatrixDeterminant(id, a, requested);
            case "rank" -> TaskSplitter.splitMatrixRank(id, a, requested);
            default -> throw new IllegalStateException(operation);
        };
    }

    Object aggregate(Job job) {
        return switch (operation) {
            case "det" -> ResultAggregator.aggregateMatrixDeterminant(job);
            case "rank" -> ResultAggregator.aggregateMatrixRank(job, a.getCols());
            case "t" -> ResultAggregator.aggregateMatrixTranspose(job, a.getRows(), a.getCols());
            default -> ResultAggregator.aggregateMatrix(job, a.getRows(), operation.equals("x") ? b.getCols() : a.getCols());
        };
    }
}
