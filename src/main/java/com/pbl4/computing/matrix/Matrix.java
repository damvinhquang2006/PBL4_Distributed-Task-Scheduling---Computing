package com.pbl4.computing.matrix;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Arrays;
import java.util.Random;

/**
 * Lớp biểu diễn ma trận 2 chiều các số thực (double), phục vụ cho các phép tính toán song song phân tán.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Matrix {
    private int rows;
    private int cols;
    private double[][] data;

    public Matrix() {
    }

    public Matrix(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.data = new double[rows][cols];
    }

    public Matrix(double[][] data) {
        if (data == null || data.length == 0) {
            this.rows = 0;
            this.cols = 0;
            this.data = new double[0][0];
        } else {
            this.rows = data.length;
            this.cols = data[0].length;
            this.data = new double[rows][cols];
            for (int i = 0; i < rows; i++) {
                System.arraycopy(data[i], 0, this.data[i], 0, cols);
            }
        }
    }

    public static Matrix zeros(int rows, int cols) {
        return new Matrix(rows, cols);
    }

    public static Matrix random(int rows, int cols, double min, double max) {
        Matrix m = new Matrix(rows, cols);
        Random rng = new Random();
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                m.data[i][j] = Math.round((min + (max - min) * rng.nextDouble()) * 10.0) / 10.0;
            }
        }
        return m;
    }

    public static Matrix identity(int n) {
        Matrix m = new Matrix(n, n);
        for (int i = 0; i < n; i++) {
            m.data[i][i] = 1.0;
        }
        return m;
    }

    public int getRows() {
        return rows;
    }

    public void setRows(int rows) {
        this.rows = rows;
    }

    public int getCols() {
        return cols;
    }

    public void setCols(int cols) {
        this.cols = cols;
    }

    public double[][] getData() {
        return data;
    }

    public void setData(double[][] data) {
        this.data = data;
        if (data != null && data.length > 0) {
            this.rows = data.length;
            this.cols = data[0].length;
        }
    }

    public double get(int r, int c) {
        return data[r][c];
    }

    public void set(int r, int c, double val) {
        data[r][c] = val;
    }

    /**
     * Trích xuất một khối dòng liên tiếp từ startRow đến endRow (không bao gồm endRow).
     */
    public double[][] sliceRows(int startRow, int endRow) {
        int count = endRow - startRow;
        double[][] slice = new double[count][cols];
        for (int i = 0; i < count; i++) {
            System.arraycopy(data[startRow + i], 0, slice[i], 0, cols);
        }
        return slice;
    }

    /**
     * Ghi đè một khối dòng kết quả vào ma trận từ startRow.
     */
    public void setRowSlice(int startRow, double[][] slice) {
        if (slice == null) return;
        for (int i = 0; i < slice.length; i++) {
            System.arraycopy(slice[i], 0, data[startRow + i], 0, cols);
        }
    }

    /**
     * In ma trận dạng trực quan trên console (giới hạn nếu ma trận quá lớn).
     */
    public String prettyPrint(int maxDisplay) {
        StringBuilder sb = new StringBuilder();
        int displayRows = Math.min(rows, maxDisplay);
        int displayCols = Math.min(cols, maxDisplay);
        
        sb.append(String.format("Matrix [%d x %d]:\n", rows, cols));
        for (int i = 0; i < displayRows; i++) {
            sb.append("  [ ");
            for (int j = 0; j < displayCols; j++) {
                sb.append(String.format("%8.2f ", data[i][j]));
            }
            if (cols > maxDisplay) {
                sb.append(" ... ");
            }
            sb.append("]\n");
        }
        if (rows > maxDisplay) {
            sb.append("  [ ... ]\n");
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Matrix matrix = (Matrix) o;
        if (rows != matrix.rows || cols != matrix.cols) return false;
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                if (Math.abs(data[i][j] - matrix.data[i][j]) > 1e-5) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(new int[]{rows, cols});
        result = 31 * result + Arrays.deepHashCode(data);
        return result;
    }
}
