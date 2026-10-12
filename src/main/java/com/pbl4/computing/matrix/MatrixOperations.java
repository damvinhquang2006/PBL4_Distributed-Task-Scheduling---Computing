package com.pbl4.computing.matrix;

import java.util.List;

/**
 * Các phép toán số học ma trận: Cộng, Trừ, Nhân ma trận và các phép tính theo từng lát cắt dòng (row-slice).
 */
public class MatrixOperations {
    private static final double EPS = 1e-9;

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

    /**
     * Tinh dinh thuc ma tran vuong bang khu Gauss co chon pivot.
     */
    public static double determinant(Matrix A) {
        if (A.getRows() != A.getCols()) {
            throw new IllegalArgumentException("Determinant requires a square matrix!");
        }

        int n = A.getRows();
        double[][] data = copyData(A);
        double det = 1.0;
        int sign = 1;

        for (int col = 0; col < n; col++) {
            int pivotRow = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(data[row][col]) > Math.abs(data[pivotRow][col])) {
                    pivotRow = row;
                }
            }

            if (Math.abs(data[pivotRow][col]) < EPS) {
                return 0.0;
            }

            if (pivotRow != col) {
                double[] tmp = data[col];
                data[col] = data[pivotRow];
                data[pivotRow] = tmp;
                sign *= -1;
            }

            double pivot = data[col][col];
            det *= pivot;
            for (int row = col + 1; row < n; row++) {
                double factor = data[row][col] / pivot;
                for (int j = col; j < n; j++) {
                    data[row][j] -= factor * data[col][j];
                }
            }
        }

        return sign * det;
    }

    /**
     * Tinh hang ma tran bang dang bac thang dong rut gon theo pivot.
     */
    public static int rank(Matrix A) {
        return rank(A.getData());
    }

    public static int rank(double[][] input) {
        if (input == null || input.length == 0) {
            return 0;
        }
        double[][] data = copyData(input);
        int rows = data.length;
        int cols = data[0].length;
        int rank = 0;

        for (int col = 0; col < cols && rank < rows; col++) {
            int pivotRow = rank;
            for (int row = rank + 1; row < rows; row++) {
                if (Math.abs(data[row][col]) > Math.abs(data[pivotRow][col])) {
                    pivotRow = row;
                }
            }

            if (Math.abs(data[pivotRow][col]) < EPS) {
                continue;
            }

            double[] tmp = data[rank];
            data[rank] = data[pivotRow];
            data[pivotRow] = tmp;

            double pivot = data[rank][col];
            for (int j = col; j < cols; j++) {
                data[rank][j] /= pivot;
            }

            for (int row = 0; row < rows; row++) {
                if (row == rank) {
                    continue;
                }
                double factor = data[row][col];
                for (int j = col; j < cols; j++) {
                    data[row][j] -= factor * data[rank][j];
                }
            }

            rank++;
        }

        return rank;
    }

    public static double[][] rowBasis(double[][] input) {
        if (input == null || input.length == 0) {
            return new double[0][0];
        }
        return rowBasis(input, input[0].length);
    }

    public static double[][] rowBasis(double[][] input, int cols) {
        if (input == null || input.length == 0) {
            return new double[0][cols];
        }
        double[][] data = copyData(input);
        int rowCount = data.length;
        int rank = 0;

        for (int col = 0; col < cols && rank < rowCount; col++) {
            int pivotRow = rank;
            for (int row = rank + 1; row < rowCount; row++) {
                if (Math.abs(data[row][col]) > Math.abs(data[pivotRow][col])) {
                    pivotRow = row;
                }
            }

            if (Math.abs(data[pivotRow][col]) < EPS) {
                continue;
            }

            double[] tmp = data[rank];
            data[rank] = data[pivotRow];
            data[pivotRow] = tmp;

            double pivot = data[rank][col];
            for (int j = col; j < cols; j++) {
                data[rank][j] /= pivot;
            }

            for (int row = 0; row < rowCount; row++) {
                if (row == rank) {
                    continue;
                }
                double factor = data[row][col];
                for (int j = col; j < cols; j++) {
                    data[row][j] -= factor * data[rank][j];
                }
            }

            rank++;
        }

        double[][] basis = new double[rank][cols];
        for (int i = 0; i < rank; i++) {
            System.arraycopy(data[i], 0, basis[i], 0, cols);
        }
        return basis;
    }

    private static double[][] copyData(Matrix matrix) {
        return copyData(matrix.getData());
    }

    private static double[][] copyData(double[][] source) {
        if (source == null || source.length == 0) {
            return new double[0][0];
        }
        int rows = source.length;
        int cols = source[0].length;
        double[][] copy = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            System.arraycopy(source[i], 0, copy[i], 0, cols);
        }
        return copy;
    }

}
