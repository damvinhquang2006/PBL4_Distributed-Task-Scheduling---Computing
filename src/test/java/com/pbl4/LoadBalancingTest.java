package com.pbl4;

import com.pbl4.core.model.LoadBalancingPolicy;
import com.pbl4.core.model.WorkerInfo;
import com.pbl4.master.WorkerRegistry;
import com.pbl4.master.scheduler.TaskScheduler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LoadBalancingTest {

    @Test
    public void testRoundRobinDistribution() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerInfo w1 = new WorkerInfo("w1", "127.0.0.1", 1001, 2);
        WorkerInfo w2 = new WorkerInfo("w2", "127.0.0.1", 1002, 2);
        WorkerInfo w3 = new WorkerInfo("w3", "127.0.0.1", 1003, 2);

        registry.register(w1);
        registry.register(w2);
        registry.register(w3);

        TaskScheduler scheduler = new TaskScheduler(registry);
        scheduler.setPolicy(LoadBalancingPolicy.ROUND_ROBIN);

        WorkerInfo first = scheduler.selectWorker();
        WorkerInfo second = scheduler.selectWorker();
        WorkerInfo third = scheduler.selectWorker();
        WorkerInfo fourth = scheduler.selectWorker();

        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(third);
        assertEquals(first.getWorkerId(), fourth.getWorkerId()); // Round robin cycles back

        registry.shutdown();
    }

    @Test
    public void testLeastLoadedDistribution() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerInfo w1 = new WorkerInfo("w1", "127.0.0.1", 1001, 2);
        WorkerInfo w2 = new WorkerInfo("w2", "127.0.0.1", 1002, 2);

        registry.register(w1);
        registry.register(w2);

        // Giả sử w1 đang có 3 task, w2 đang có 0 task
        w1.incrementActiveTasks();
        w1.incrementActiveTasks();
        w1.incrementActiveTasks();

        TaskScheduler scheduler = new TaskScheduler(registry);
        scheduler.setPolicy(LoadBalancingPolicy.LEAST_LOADED);

        WorkerInfo chosen = scheduler.selectWorker();
        assertEquals("w2", chosen.getWorkerId());

        registry.shutdown();
    }
}
