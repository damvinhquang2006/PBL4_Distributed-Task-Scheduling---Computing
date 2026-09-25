package com.pbl4.computing.matrix;

import java.util.List;

/**
 * Các phép toán số học ma trận: Cộng, Trừ, Nhân ma trận và các phép tính theo từng lát cắt dòng (row-slice).
 */
public class MatrixOperations {

    /**
     * Cộng 2 ma trận cục bộ C = A + B.
     */
    public static Matrix add(Matrix A, Matrix B) {
        if (A.getRows() != B.getRows() || A.getCols() != B.getCols()) {
            throw new IllegalArgumentException("Matrix dimensions must match for addition!");
        }
        int r = A.getRows();
        int c = A.getCols();
        Matrix C = new Matrix(r, c);
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < c; j++) {
                C.set(i, j, A.get(i, j) + B.get(i, j));
            }
        }
        return C;
    }

    /**
     * Trừ 2 ma trận cục bộ C = A - B.
     */
    public static Matrix subtract(Matrix A, Matrix B) {
        if (A.getRows() != B.getRows() || A.getCols() != B.getCols()) {
            throw new IllegalArgumentException("Matrix dimensions must match for subtraction!");
        }
        int r = A.getRows();
        int c = A.getCols();
        Matrix C = new Matrix(r, c);
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < c; j++) {
                C.set(i, j, A.get(i, j) - B.get(i, j));
            }
        }
        return C;
    }

    /**
     * Nhân 2 ma trận cục bộ C = A * B (Đơn luồng - Baseline).
     */
    public static Matrix multiply(Matrix A, Matrix B) {
        if (A.getCols() != B.getRows()) {
            throw new IllegalArgumentException(String.format(
                    "Cannot multiply matrix [%d x %d] with [%d x %d]!",
                    A.getRows(), A.getCols(), B.getRows(), B.getCols()));
        }
        int r = A.getRows();
        int kDim = A.getCols();
        int c = B.getCols();
        Matrix C = new Matrix(r, c);
        double[][] aData = A.getData();
        double[][] bData = B.getData();
        double[][] cData = C.getData();

        for (int i = 0; i < r; i++) {
            for (int k = 0; k < kDim; k++) {
                double aVal = aData[i][k];
                for (int j = 0; j < c; j++) {
                    cData[i][j] += aVal * bData[k][j];
                }
            }
        }
        return C;
    }

    /**
     * Nhân chuỗi nhiều ma trận liên tiếp: M1 * M2 * M3 * ... * Mn
     */
    public static Matrix multiplyChain(List<Matrix> list) {
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("Matrix list cannot be empty!");
        }
        Matrix res = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            res = multiply(res, list.get(i));
        }
        return res;
    }

    /**
     * Phép toán tính toán trên Worker: Nhân một lát cắt dòng của A với toàn bộ B.
     * C_slice = A_slice * B
     */
    public static double[][] multiplySlice(double[][] sliceA, double[][] dataB, int colsB) {
        int sliceRows = sliceA.length;
        int kDim = sliceA[0].length;
        double[][] resultSlice = new double[sliceRows][colsB];

        for (int i = 0; i < sliceRows; i++) {
            for (int k = 0; k < kDim; k++) {
                double aVal = sliceA[i][k];
                for (int j = 0; j < colsB; j++) {
                    resultSlice[i][j] += aVal * dataB[k][j];
                }
            }
        }
        return resultSlice;
    }

    /**
     * Phép toán tính toán trên Worker: Cộng một lát cắt dòng của A với lát cắt tương ứng của B.
     */
    public static double[][] addSlice(double[][] sliceA, double[][] sliceB) {
        int rows = sliceA.length;
        int cols = sliceA[0].length;
        double[][] result = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[i][j] = sliceA[i][j] + sliceB[i][j];
            }
        }
        return result;
    }

    /**
     * Phep toan tinh toan tren Worker: Tru mot lat cat dong cua A voi lat cat tuong ung cua B.
     */
    public static double[][] subSlice(double[][] sliceA, double[][] sliceB) {
        int rows = sliceA.length;
        int cols = sliceA[0].length;
        double[][] result = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[i][j] = sliceA[i][j] - sliceB[i][j];
            }
        }
        return result;
    }

    /**
     * Chia lay du 2 ma tran cuc bo C = A % B.
     */
    public static Matrix modulo(Matrix A, Matrix B) {
        if (A.getRows() != B.getRows() || A.getCols() != B.getCols()) {
            throw new IllegalArgumentException("Matrix dimensions must match for modulo!");
        }
        int r = A.getRows();
        int c = A.getCols();
        Matrix C = new Matrix(r, c);
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < c; j++) {
                double bVal = B.get(i, j);
                if (Math.abs(bVal) < 1e-9) {
                    C.set(i, j, 0.0);
                } else {
                    C.set(i, j, A.get(i, j) % bVal);
                }
            }
        }
        return C;
    }

    /**
     * Phep toan tinh toan tren Worker: Chia lay du mot lat cat dong cua A voi lat cat tuong ung cua B.
     */
    public static double[][] modSlice(double[][] sliceA, double[][] sliceB) {
        int rows = sliceA.length;
        int cols = sliceA[0].length;
        double[][] result = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                double bVal = sliceB[i][j];
                if (Math.abs(bVal) < 1e-9) {
                    result[i][j] = 0.0;
                } else {
                    result[i][j] = sliceA[i][j] % bVal;
                }
            }
        }
        return result;
    }
}
