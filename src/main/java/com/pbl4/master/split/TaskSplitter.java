package com.pbl4.master.split;

import com.pbl4.computing.graph.Graph;
import com.pbl4.computing.matrix.Matrix;
import com.pbl4.core.model.Task;
import com.pbl4.core.model.TaskType;

import java.util.ArrayList;
import java.util.List;

/**
 * Bộ chia nhỏ công việc lớn thành các đơn vị công việc (Task) con độc lập để phân phối cho các Worker.
 */
public class TaskSplitter {

    /**
     * Chia nhỏ biểu thức số học khởi đầu (Ví dụ: 1+2 và 3*2).
     */
    public static List<Task> splitArithmetic(String jobId, String leftExpr, String rightExpr) {
        List<Task> tasks = new ArrayList<>();

        Task t1 = new Task(jobId + "-task-01", jobId, TaskType.ARITHMETIC);
        t1.addPayload("expression", leftExpr);
        tasks.add(t1);

        Task t2 = new Task(jobId + "-task-02", jobId, TaskType.ARITHMETIC);
        t2.addPayload("expression", rightExpr);
        tasks.add(t2);

        return tasks;
    }

    /**
     * Chia nhỏ phép cộng/trừ ma trận theo dải dòng (row strips).
     */
    public static List<Task> splitMatrixAddSub(String jobId, Matrix A, Matrix B, TaskType type, int numWorkers) {
        if (numWorkers <= 0) numWorkers = 1;
        int totalRows = A.getRows();
        int partitions = Math.min(numWorkers, totalRows);
        int rowsPerTask = totalRows / partitions;
        int remainder = totalRows % partitions;

        List<Task> tasks = new ArrayList<>();
        int currentRow = 0;

        for (int i = 0; i < partitions; i++) {
            int currentBatch = rowsPerTask + (i < remainder ? 1 : 0);
            int startRow = currentRow;
            int endRow = startRow + currentBatch;

            double[][] sliceA = A.sliceRows(startRow, endRow);
            double[][] sliceB = B.sliceRows(startRow, endRow);

            Task task = new Task(String.format("%s-task-%02d", jobId, i + 1), jobId, type);
            task.addPayload("startRow", startRow);
            task.addPayload("sliceA", sliceA);
            task.addPayload("sliceB", sliceB);

            tasks.add(task);
            currentRow = endRow;
        }

        return tasks;
    }

    /**
     * Chia nhỏ phép nhân 2 ma trận A x B theo khối dòng của A.
     * Mỗi worker nhận một lát cắt dòng của A và toàn bộ ma trận B.
     */
    public static List<Task> splitMatrixMultiply(String jobId, Matrix A, Matrix B, int numWorkers) {
        if (numWorkers <= 0) numWorkers = 1;
        int totalRows = A.getRows();
        int partitions = Math.min(numWorkers, totalRows);
        int rowsPerTask = totalRows / partitions;
        int remainder = totalRows % partitions;

        List<Task> tasks = new ArrayList<>();
        int currentRow = 0;

        for (int i = 0; i < partitions; i++) {
            int currentBatch = rowsPerTask + (i < remainder ? 1 : 0);
            int startRow = currentRow;
            int endRow = startRow + currentBatch;

            double[][] sliceA = A.sliceRows(startRow, endRow);

            Task task = new Task(String.format("%s-task-%02d", jobId, i + 1), jobId, TaskType.MATRIX_MULTIPLY);
            task.addPayload("startRow", startRow);
            task.addPayload("sliceA", sliceA);
            task.addPayload("dataB", B.getData());
            task.addPayload("colsB", B.getCols());

            tasks.add(task);
            currentRow = endRow;
        }

        return tasks;
    }

    /**
     * Chia nhỏ bài toán tìm đường đi ngắn nhất đồ thị (All-Pairs Shortest Path).
     * Tập các đỉnh nguồn [0..V-1] được phân chia đều cho các Workers.
     */
    public static List<Task> splitGraphShortestPath(String jobId, Graph graph, int numWorkers) {
        if (numWorkers <= 0) numWorkers = 1;
        int V = graph.getVertices();
        int partitions = Math.min(numWorkers, V);
        int sourcesPerTask = V / partitions;
        int remainder = V % partitions;

        List<Task> tasks = new ArrayList<>();
        int currentSource = 0;

        for (int i = 0; i < partitions; i++) {
            int currentBatch = sourcesPerTask + (i < remainder ? 1 : 0);
            List<Integer> assignedSources = new ArrayList<>();
            for (int k = 0; k < currentBatch; k++) {
                assignedSources.add(currentSource++);
            }

            Task task = new Task(String.format("%s-task-%02d", jobId, i + 1), jobId, TaskType.GRAPH_SHORTEST_PATH);
            task.addPayload("graph", graph);
            task.addPayload("sourceVertices", assignedSources);

            tasks.add(task);
        }

        return tasks;
    }

    /**
     * Chia dinh thuc theo cac so hang khai trien Laplace tren dong dau tien.
     */
    public static List<Task> splitMatrixDeterminant(String jobId, Matrix A, int numWorkers) {
        if (numWorkers <= 0) numWorkers = 1;
        int totalCols = A.getCols();
        int partitions = Math.min(numWorkers, totalCols);
        int colsPerTask = totalCols / partitions;
        int remainder = totalCols % partitions;

        List<Task> tasks = new ArrayList<>();
        int currentCol = 0;

        for (int i = 0; i < partitions; i++) {
            int currentBatch = colsPerTask + (i < remainder ? 1 : 0);
            List<Integer> assignedColumns = new ArrayList<>();
            for (int k = 0; k < currentBatch; k++) {
                assignedColumns.add(currentCol++);
            }

            Task task = new Task(String.format("%s-task-%02d", jobId, i + 1), jobId, TaskType.MATRIX_DETERMINANT);
            task.addPayload("matrixData", A.getData());
            task.addPayload("assignedColumns", assignedColumns);

            tasks.add(task);
        }

        return tasks;
    }

    /**
     * Chia hang ma tran theo lat cat dong; Worker rut gon lat cat thanh basis cuc bo.
     */
    public static List<Task> splitMatrixRank(String jobId, Matrix A, int numWorkers) {
        if (numWorkers <= 0) numWorkers = 1;
        int totalRows = A.getRows();
        int partitions = Math.min(numWorkers, totalRows);
        int rowsPerTask = totalRows / partitions;
        int remainder = totalRows % partitions;

        List<Task> tasks = new ArrayList<>();
        int currentRow = 0;

        for (int i = 0; i < partitions; i++) {
            int currentBatch = rowsPerTask + (i < remainder ? 1 : 0);
            int startRow = currentRow;
            int endRow = startRow + currentBatch;

            Task task = new Task(String.format("%s-task-%02d", jobId, i + 1), jobId, TaskType.MATRIX_RANK);
            task.addPayload("startRow", startRow);
            task.addPayload("sliceA", A.sliceRows(startRow, endRow));
            task.addPayload("cols", A.getCols());

            tasks.add(task);
            currentRow = endRow;
        }

        return tasks;
    }
}
