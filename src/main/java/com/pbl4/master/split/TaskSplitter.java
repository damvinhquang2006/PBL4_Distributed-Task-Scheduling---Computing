package com.pbl4.master.split;

import com.pbl4.computing.graph.Graph;
import com.pbl4.computing.matrix.Matrix;
import com.pbl4.core.model.*;
import java.util.*;
import java.util.function.BiFunction;

public class TaskSplitter {
    private static List<Task> chunks(String job, TaskType type, int units, int requested,
                                      BiFunction<Integer, Integer, Map<String, Object>> factory) {
        if (units <= 0) throw new IllegalArgumentException("Input must have at least one row/column");
        int count = Math.min(units, Math.max(1, requested));
        List<Task> tasks = new ArrayList<>(); int offset = 0;
        for (int i = 0; i < count; i++) {
            int start = offset, end = start + units / count + (i < units % count ? 1 : 0); offset = end;
            Task task = new Task(String.format("%s-task-%02d", job, i + 1), job, type);
            task.deferPayload(() -> factory.apply(start, end)); tasks.add(task);
        }
        return tasks;
    }
    public static List<Task> splitArithmetic(String job, String left, String right) {
        return List.of(new Task(job + "-task-01", job, TaskType.ARITHMETIC).addPayload("expression", left),
            new Task(job + "-task-02", job, TaskType.ARITHMETIC).addPayload("expression", right));
    }
    public static List<Task> splitMatrixAddSub(String job, Matrix a, Matrix b, TaskType type, int count) {
        if (a.getRows() != b.getRows() || a.getCols() != b.getCols() || a.getCols() <= 0)
            throw new IllegalArgumentException("Matrix dimensions must match and be positive");
        return chunks(job, type, a.getRows(), count, (start,end) -> new HashMap<>(Map.of(
            "startRow", start, "sliceA", a.sliceRows(start,end), "sliceB", b.sliceRows(start,end))));
    }
    public static List<Task> splitMatrixMultiply(String job, Matrix a, Matrix b, int count) {
        if (a.getCols() != b.getRows() || a.getCols() <= 0 || b.getCols() <= 0)
            throw new IllegalArgumentException("Incompatible multiplication dimensions");
        return chunks(job, TaskType.MATRIX_MULTIPLY, a.getRows(), count, (start,end) -> new HashMap<>(Map.of(
            "startRow", start, "sliceA", a.sliceRows(start,end), "dataB", b.getData(), "colsB", b.getCols())));
    }
    public static List<Task> splitMatrixDeterminant(String job, Matrix a, int count) {
        if (a.getRows() != a.getCols()) throw new IllegalArgumentException("Square matrix required");
        return chunks(job, TaskType.MATRIX_DETERMINANT, a.getCols(), count, (start,end) -> new HashMap<>(Map.of(
            "matrixData", a.getData(), "assignedColumns", java.util.stream.IntStream.range(start,end).boxed().toList())));
    }
    public static List<Task> splitMatrixRank(String job, Matrix a, int count) {
        if (a.getCols() <= 0) throw new IllegalArgumentException("Positive columns required");
        return chunks(job, TaskType.MATRIX_RANK, a.getRows(), count, (start,end) -> new HashMap<>(Map.of(
            "startRow", start, "sliceA", a.sliceRows(start,end), "cols", a.getCols())));
    }
    public static List<Task> splitMatrixTranspose(String job, Matrix a, int count) {
        if (a.getRows() <= 0 || a.getCols() <= 0)
            throw new IllegalArgumentException("Positive rows and columns required");
        return chunks(job, TaskType.MATRIX_TRANSPOSE, a.getRows(), count, (start,end) -> new HashMap<>(Map.of(
            "startRow", start, "sliceA", a.sliceRows(start,end), "totalCols", a.getCols())));
    }
    public static List<Task> splitMatrixScalarMul(String job, Matrix a, double scalar, int count) {
        if (a.getRows() <= 0 || a.getCols() <= 0)
            throw new IllegalArgumentException("Positive rows and columns required");
        return chunks(job, TaskType.MATRIX_SCALAR_MUL, a.getRows(), count, (start,end) -> new HashMap<>(Map.of(
            "startRow", start, "sliceA", a.sliceRows(start,end), "scalar", scalar)));
    }
    public static List<Task> splitMatrixInverse(String job, Matrix a, int count) {
        if (a.getRows() != a.getCols() || a.getRows() <= 0)
            throw new IllegalArgumentException("Positive square matrix required for inverse");
        return chunks(job, TaskType.MATRIX_INVERSE, a.getRows(), count, (start,end) -> new HashMap<>(Map.of(
            "startRow", start, "endRow", end, "matrixData", a.getData(), "n", a.getRows())));
    }
    public static List<Task> splitGraphShortestPath(String job, Graph graph, int count) {
        return chunks(job, TaskType.GRAPH_SHORTEST_PATH, graph.getVertices(), count, (start,end) -> new HashMap<>(Map.of(
            "graph", graph, "sourceVertices", java.util.stream.IntStream.range(start,end).boxed().toList())));
    }
}
