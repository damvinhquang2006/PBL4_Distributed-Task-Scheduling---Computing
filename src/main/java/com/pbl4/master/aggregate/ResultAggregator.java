package com.pbl4.master.aggregate;

import com.pbl4.computing.matrix.Matrix;
import com.pbl4.core.model.Job;
import com.pbl4.core.model.TaskResult;
import com.pbl4.core.network.JsonUtils;

import java.util.Comparator;
import java.util.List;

/**
 * Bo phan tong hop cac ket qua con (sub-results) tu cac Worker gui ve thanh ket qua hoan chinh cua Job.
 */
public class ResultAggregator {

    /**
     * Tong hop bieu thuc khoi dau: Lay ket qua Task 1 (VD: 3) va Task 2 (VD: 6), tinh 3 + 6 = 9.
     */
    public static double aggregateArithmetic(Job job, String topOperator) {
        List<TaskResult> results = job.getResults().values().stream()
                .sorted(Comparator.comparing(TaskResult::getTaskId))
                .toList();

        if (results.size() < 2) {
            throw new IllegalStateException("Can it nhat 2 ket qua de tong hop bieu thuc!");
        }

        double val1 = ((Number) results.get(0).getResultValue("value")).doubleValue();
        double val2 = ((Number) results.get(1).getResultValue("value")).doubleValue();

        double finalRes;
        switch (topOperator) {
            case "+":
                finalRes = val1 + val2;
                break;
            case "-":
                finalRes = val1 - val2;
                break;
            case "*":
                finalRes = val1 * val2;
                break;
            case "/":
                finalRes = val1 / val2;
                break;
            default:
                finalRes = val1 + val2;
        }

        job.setFinalOutput(finalRes);
        return finalRes;
    }

    /**
     * Tong hop cac lat cat ma tran tu cac Worker thanh ma tran ket qua hoan chinh.
     */
    public static Matrix aggregateMatrix(Job job, int totalRows, int totalCols) {
        Matrix resultMatrix = new Matrix(totalRows, totalCols);

        for (TaskResult taskResult : job.getResults().values()) {
            if (!taskResult.isSuccess()) {
                throw new RuntimeException("Task " + taskResult.getTaskId() + " that bai: " + taskResult.getErrorMessage());
            }

            int startRow = ((Number) taskResult.getResultValue("startRow")).intValue();
            double[][] sliceData = parse2DArray(taskResult.getResultValue("sliceResult"));
            resultMatrix.setRowSlice(startRow, sliceData);
        }

        job.setFinalOutput(resultMatrix);
        return resultMatrix;
    }

    /**
     * Tong hop ket qua tim duong di ngan nhat cua cac nhom dinh thanh ma tran khoang cach toan the.
     */
    public static double[][] aggregateGraphShortestPath(Job job, int totalVertices) {
        double[][] allDist = new double[totalVertices][totalVertices];

        for (TaskResult taskResult : job.getResults().values()) {
            if (!taskResult.isSuccess()) {
                throw new RuntimeException("Task " + taskResult.getTaskId() + " that bai: " + taskResult.getErrorMessage());
            }

            List<?> sources = (List<?>) taskResult.getResultValue("sourceVertices");
            double[][] distances = parse2DArray(taskResult.getResultValue("distances"));

            for (int i = 0; i < sources.size(); i++) {
                int src = ((Number) sources.get(i)).intValue();
                System.arraycopy(distances[i], 0, allDist[src], 0, totalVertices);
            }
        }

        job.setFinalOutput(allDist);
        return allDist;
    }

    /**
     * Tong hop cac tong rieng cofactor thanh dinh thuc cuoi cung.
     */
    public static double aggregateMatrixDeterminant(Job job) {
        double determinant = 0.0;
        for (TaskResult taskResult : job.getResults().values()) {
            if (!taskResult.isSuccess()) {
                throw new RuntimeException("Task " + taskResult.getTaskId() + " that bai: " + taskResult.getErrorMessage());
            }
            determinant += ((Number) taskResult.getResultValue("partialDeterminant")).doubleValue();
        }

        job.setFinalOutput(determinant);
        return determinant;
    }

    /**
     * Tong hop basis cuc bo tu cac Worker roi tinh hang toan cuc.
     */
    public static int aggregateMatrixRank(Job job, int cols) {
        int totalBasisRows = 0;
        List<TaskResult> results = job.getResults().values().stream()
                .sorted(Comparator.comparing(TaskResult::getTaskId))
                .toList();

        for (TaskResult taskResult : results) {
            if (!taskResult.isSuccess()) {
                throw new RuntimeException("Task " + taskResult.getTaskId() + " that bai: " + taskResult.getErrorMessage());
            }
            double[][] basisRows = parse2DArray(taskResult.getResultValue("basisRows"));
            totalBasisRows += basisRows.length;
        }

        double[][] combinedBasis = new double[totalBasisRows][cols];
        int rowOffset = 0;
        for (TaskResult taskResult : results) {
            double[][] basisRows = parse2DArray(taskResult.getResultValue("basisRows"));
            for (double[] basisRow : basisRows) {
                System.arraycopy(basisRow, 0, combinedBasis[rowOffset++], 0, cols);
            }
        }

        int rank = com.pbl4.computing.matrix.MatrixOperations.rank(combinedBasis);
        job.setFinalOutput(rank);
        return rank;
    }

    private static double[][] parse2DArray(Object obj) {
        if (obj instanceof double[][]) {
            return (double[][]) obj;
        }
        return JsonUtils.getMapper().convertValue(obj, double[][].class);
    }
}
