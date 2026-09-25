package com.pbl4;

import com.pbl4.computing.matrix.Matrix;
import com.pbl4.computing.matrix.MatrixOperations;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MatrixComputationTest {

    @Test
    public void testMatrixAdditionAndSubtraction() {
        Matrix A = new Matrix(new double[][]{
                {1, 2, 3},
                {4, 5, 6}
        });
        Matrix B = new Matrix(new double[][]{
                {7, 8, 9},
                {1, 2, 3}
        });

        Matrix C = MatrixOperations.add(A, B);
        assertEquals(8.0, C.get(0, 0));
        assertEquals(10.0, C.get(0, 1));
        assertEquals(12.0, C.get(0, 2));
        assertEquals(5.0, C.get(1, 0));

        Matrix D = MatrixOperations.subtract(C, B);
        assertEquals(A, D);
    }

    @Test
    public void testMatrixMultiplication() {
        Matrix A = new Matrix(new double[][]{
                {1, 2},
                {3, 4}
        });
        Matrix B = new Matrix(new double[][]{
                {5, 6},
                {7, 8}
        });

        // C = A * B = [ [1*5 + 2*7, 1*6 + 2*8], [3*5 + 4*7, 3*6 + 4*8] ] = [ [19, 22], [43, 50] ]
        Matrix C = MatrixOperations.multiply(A, B);
        assertEquals(19.0, C.get(0, 0));
        assertEquals(22.0, C.get(0, 1));
        assertEquals(43.0, C.get(1, 0));
        assertEquals(50.0, C.get(1, 1));
    }

    @Test
    public void testRowSliceMultiplication() {
        Matrix A = new Matrix(new double[][]{
                {1, 2},
                {3, 4}
        });
        Matrix B = new Matrix(new double[][]{
                {5, 6},
                {7, 8}
        });

        double[][] row0 = A.sliceRows(0, 1);
        double[][] sliceRes = MatrixOperations.multiplySlice(row0, B.getData(), B.getCols());

        assertEquals(19.0, sliceRes[0][0]);
        assertEquals(22.0, sliceRes[0][1]);
    }

    @Test
    public void testMatrixChain() {
        Matrix A = Matrix.identity(3);
        Matrix B = Matrix.random(3, 3, 1, 5);
        Matrix C = Matrix.identity(3);

        List<Matrix> chain = Arrays.asList(A, B, C);
        Matrix res = MatrixOperations.multiplyChain(chain);
        assertEquals(B, res);
    }

    @Test
    public void testMatrixModulo() {
        Matrix A = new Matrix(new double[][]{
                {10, 15, 22},
                {7, 9, 13}
        });
        Matrix B = new Matrix(new double[][]{
                {3, 4, 5},
                {2, 3, 4}
        });

        Matrix C = MatrixOperations.modulo(A, B);
        assertEquals(1.0, C.get(0, 0)); // 10 % 3 = 1
        assertEquals(3.0, C.get(0, 1)); // 15 % 4 = 3
        assertEquals(2.0, C.get(0, 2)); // 22 % 5 = 2
        assertEquals(1.0, C.get(1, 0)); // 7 % 2 = 1
        assertEquals(0.0, C.get(1, 1)); // 9 % 3 = 0
        assertEquals(1.0, C.get(1, 2)); // 13 % 4 = 1
    }
}
