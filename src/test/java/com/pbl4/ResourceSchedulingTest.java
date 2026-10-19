package com.pbl4;

import com.pbl4.core.model.*;
import com.pbl4.master.WorkerRegistry;
import com.pbl4.master.scheduler.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceSchedulingTest {
    private WorkerInfo worker(String id, int capacity) {
        WorkerInfo w = new WorkerInfo(id,"127.0.0.1",5001,capacity); w.sessionId=id; return w;
    }
    @Test void metricsHysteresisUnknownAndPause() {
        WorkerRegistry registry = new WorkerRegistry();
        try {
            WorkerInfo w = worker("a",2); registry.register(w);
            TaskScheduler scheduler = new TaskScheduler(registry); scheduler.setPolicy(LoadBalancingPolicy.RESOURCE_AWARE);
            w.metrics = new ResourceMetrics(85.,20.,20.,0); assertNull(scheduler.reserveWorker());
            w.metrics = new ResourceMetrics(80.,20.,20.,0); assertNull(scheduler.reserveWorker());
            w.metrics = new ResourceMetrics(74.,20.,20.,0); assertSame(w,scheduler.reserveWorker()); scheduler.release(w);
            w.metrics = new ResourceMetrics(null,90.,20.,0); assertNull(scheduler.reserveWorker());
            w.metrics = new ResourceMetrics(null,79.,85.,0); assertNull(scheduler.reserveWorker());
            w.metrics = new ResourceMetrics(null,null,null,0); assertSame(w,scheduler.reserveWorker()); scheduler.release(w);
            w.paused=true; registry.heartbeat("a","a",w.metrics); assertNull(scheduler.reserveWorker()); assertTrue(w.paused);
            assertTrue(w.metrics.toString().contains("UNKNOWN"));
        } finally { registry.shutdown(); }
    }
    @Test void normalizedLoadAndAtomicCapacity() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            WorkerInfo a=worker("a",1), b=worker("b",4); registry.register(a); registry.register(b);
            a.metrics=new ResourceMetrics(10.,20.,20.,0); b.metrics=new ResourceMetrics(20.,20.,20.,0);
            TaskScheduler scheduler=new TaskScheduler(registry); scheduler.setPolicy(LoadBalancingPolicy.RESOURCE_AWARE);
            assertSame(a,scheduler.reserveWorker()); assertSame(b,scheduler.reserveWorker());
            List<Future<WorkerInfo>> futures=new ArrayList<>();
            for(int i=0;i<20;i++) futures.add(pool.submit(scheduler::reserveWorker));
            int admitted=0; for(var f:futures) if(f.get()!=null) admitted++;
            assertEquals(3,admitted); assertEquals(1,a.getActiveTasks()); assertEquals(4,b.getActiveTasks());
        } finally { pool.shutdownNow(); registry.shutdown(); }
    }
    @Test void invalidThresholdsRejected() {
        assertThrows(IllegalArgumentException.class,()->new ResourceThresholds(70,80,90,80,85,75));
        assertNull(ResourceMetrics.valid(Double.NaN));
    }
}
