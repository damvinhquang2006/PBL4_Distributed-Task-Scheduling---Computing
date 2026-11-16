package com.pbl4;

import com.pbl4.core.model.WorkerInfo;
import com.pbl4.core.model.WorkerStatus;
import com.pbl4.master.MasterServer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkActiveWorkersTest {

    private WorkerInfo createWorker(String id, int port) {
        WorkerInfo w = new WorkerInfo(id, "127.0.0.1", port, 2);
        w.sessionId = id;
        w.machineId = "laptop-1";
        w.machineName = "Test Laptop";
        w.hostParallelism = 4;
        return w;
    }

    @Test
    void testActiveWorkerLimit() {
        MasterServer master = new MasterServer(19876);
        try {
            // Register 4 workers
            WorkerInfo w1 = createWorker("worker-01", 19877);
            WorkerInfo w2 = createWorker("worker-02", 19878);
            WorkerInfo w3 = createWorker("worker-03", 19879);
            WorkerInfo w4 = createWorker("worker-04", 19880);

            master.getRegistry().register(w1);
            master.getRegistry().register(w2);
            master.getRegistry().register(w3);
            master.getRegistry().register(w4);

            assertEquals(4, master.getRegistry().getAvailableWorkers().size());

            // Limit to 1 worker for baseline benchmark
            List<String> active1 = master.setActiveWorkerLimit(1);
            assertEquals(1, active1.size());
            assertEquals("worker-01", active1.get(0));
            assertFalse(w1.paused);
            assertTrue(w2.paused);
            assertTrue(w3.paused);
            assertTrue(w4.paused);
            assertEquals(1, master.getRegistry().getAvailableWorkers().size());

            // Limit to 3 workers
            List<String> active3 = master.setActiveWorkerLimit(3);
            assertEquals(3, active3.size());
            assertFalse(w1.paused);
            assertFalse(w2.paused);
            assertFalse(w3.paused);
            assertTrue(w4.paused);
            assertEquals(3, master.getRegistry().getAvailableWorkers().size());

            // Unpause all (count = 0 or count >= total)
            List<String> activeAll = master.setActiveWorkerLimit(0);
            assertEquals(4, activeAll.size());
            assertFalse(w1.paused);
            assertFalse(w2.paused);
            assertFalse(w3.paused);
            assertFalse(w4.paused);
            assertEquals(4, master.getRegistry().getAvailableWorkers().size());

        } finally {
            master.stop();
        }
    }
}
