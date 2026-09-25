package com.pbl4;

import com.pbl4.computing.graph.Graph;
import com.pbl4.computing.graph.ShortestPathAlgorithms;
import com.pbl4.computing.matrix.Matrix;
import com.pbl4.computing.matrix.MatrixOperations;
import com.pbl4.master.MasterServer;
import com.pbl4.worker.WorkerConfig;
import com.pbl4.worker.WorkerNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DistributedComputingIntegrationTest {
    private static final int MASTER_PORT = 5050;
    private static final int[] WORKER_PORTS = {5051, 5052, 5053};

    private static MasterServer masterServer;
    private static final List<WorkerNode> workers = new ArrayList<>();

    @BeforeAll
    public static void setupCluster() throws Exception {
        // Khởi động Master
        masterServer = new MasterServer(MASTER_PORT);
        masterServer.start();
        Thread.sleep(300);

        // Khởi động 3 Workers
        for (int i = 0; i < WORKER_PORTS.length; i++) {
            WorkerConfig cfg = new WorkerConfig("test-worker-0" + (i + 1), "127.0.0.1", WORKER_PORTS[i], "127.0.0.1", MASTER_PORT);
            WorkerNode node = new WorkerNode(cfg);
            node.start();
            workers.add(node);
        }

        // Đợi đăng ký
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 5000) {
            if (masterServer.getRegistry().getAvailableWorkers().size() >= 3) {
                break;
            }
            Thread.sleep(200);
        }
        assertEquals(3, masterServer.getRegistry().getAvailableWorkers().size(), "Can dung 3 Worker truc tuyen de chay test!");
    }

    @AfterAll
    public static void teardownCluster() {
        for (WorkerNode w : workers) {
            w.stop();
        }
        if (masterServer != null) {
            masterServer.stop();
        }
    }

    @Test
    public void testStarterArithmeticCalculation() {
        // Đề bài yêu cầu: Gửi 1+2 đến client01, 3*2 đến client02 -> Master tổng hợp xuất ra 9
        Double result = masterServer.runArithmeticStarter("1+2", "3*2", "+");
        assertNotNull(result);
        assertEquals(9.0, result, 1e-6);
    }

    @Test
    public void testDistributedMatrixAddition() {
        Matrix A = Matrix.random(12, 12, 1.0, 10.0);
        Matrix B = Matrix.random(12, 12, 1.0, 10.0);

        Matrix distributedResult = masterServer.runMatrixAddOrSub(A, B, true);
        assertNotNull(distributedResult);

        Matrix expected = MatrixOperations.add(A, B);
        assertEquals(expected, distributedResult);
    }

    @Test
    public void testDistributedMatrixMultiplication() {
        Matrix A = Matrix.random(15, 12, 1.0, 5.0);
        Matrix B = Matrix.random(12, 15, 1.0, 5.0);

        Matrix distributedResult = masterServer.runMatrixMultiplication(A, B);
        assertNotNull(distributedResult);

        Matrix expected = MatrixOperations.multiply(A, B);
        assertEquals(expected, distributedResult);
    }

    @Test
    public void testDistributedGraphShortestPath() {
        Graph g = Graph.randomGraph(10, 0.4, 20.0);

        double[][] distributedDist = masterServer.runGraphShortestPath(g);
        assertNotNull(distributedDist);

        double[][] expectedDist = ShortestPathAlgorithms.allPairsDijkstraSequential(g);

        for (int i = 0; i < 10; i++) {
            assertArrayEquals(expectedDist[i], distributedDist[i], 1e-5);
        }
    }

    @Test
    public void testDistributedMatrixModulo() {
        Matrix A = Matrix.random(12, 12, 10.0, 50.0);
        Matrix B = Matrix.random(12, 12, 2.0, 9.0);

        Matrix distributedResult = masterServer.runMatrixModulo(A, B);
        assertNotNull(distributedResult);

        Matrix expected = MatrixOperations.modulo(A, B);
        assertEquals(expected, distributedResult);
    }
}
