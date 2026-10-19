package com.pbl4;

import com.pbl4.core.model.*;
import com.pbl4.master.WorkerRegistry;
import com.pbl4.master.scheduler.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DispatcherRecoveryTest {
    static class CaptureScheduler extends TaskScheduler {
        final BlockingQueue<Task> sent = new LinkedBlockingQueue<>();
        CaptureScheduler(WorkerRegistry r) { super(r); }
        @Override public Message sendReservedTask(Task task, WorkerInfo w) { sent.add(task); return new Message(MessageType.TASK_ACCEPTED,"fake"); }
    }
    static WorkerInfo worker(String id) {
        WorkerInfo w = new WorkerInfo(id,"127.0.0.1",1,1); w.sessionId=id; return w;
    }
    static Job job() {
        String id=UUID.randomUUID().toString(); Job job=new Job(id,"test",TaskType.ARITHMETIC);
        job.addTask(new Task(id+"-1",id,TaskType.ARITHMETIC).addPayload("expression","1+2")); return job;
    }
    static TaskResult result(Task t,String worker,boolean success) {
        TaskResult r = success ? TaskResult.success(t.getTaskId(),t.getJobId(),worker) : TaskResult.failure(t.getTaskId(),t.getJobId(),worker,"bad input");
        r.attemptId=t.attemptId; r.masterSession=t.masterSession; r.workerSession=t.workerSession; r.addResult("value",3); return r;
    }
    @Test void workerLossRequeuesAndIgnoresLateDuplicateResults() throws Exception {
        WorkerRegistry registry=new WorkerRegistry(); CaptureScheduler scheduler=new CaptureScheduler(registry);
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try(JobDispatcher dispatcher=new JobDispatcher("master",scheduler)) {
            WorkerInfo a=worker("a"),b=worker("b"); registry.register(a); registry.register(b);
            Job job=job(); Future<Boolean> finished=caller.submit(()->dispatcher.execute(job,5000));
            Task first=scheduler.sent.poll(2,TimeUnit.SECONDS); assertNotNull(first);
            a.setStatus(WorkerStatus.OFFLINE); dispatcher.workerFailed(a);
            Task second=scheduler.sent.poll(2,TimeUnit.SECONDS); assertNotNull(second); assertNotEquals(first.attemptId,second.attemptId);
            dispatcher.result(result(first,"a",true)); assertEquals(0,job.getResults().size());
            TaskResult valid=result(second,"b",true); dispatcher.result(valid); dispatcher.result(valid);
            assertTrue(finished.get(2,TimeUnit.SECONDS)); assertEquals(1,b.getCompletedTasks()); assertEquals(0,b.getActiveTasks());
            assertEquals(1,job.getResults().size()); assertNotEquals(JobStatus.COMPLETED,job.getStatus());
        } finally { caller.shutdownNow(); registry.shutdown(); }
    }
    @Test void queueWaitsForWorkerAndFailsOnComputationError() throws Exception {
        WorkerRegistry registry=new WorkerRegistry(); CaptureScheduler scheduler=new CaptureScheduler(registry);
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try(JobDispatcher dispatcher=new JobDispatcher("master",scheduler)) {
            Job job=job(); Future<Boolean> finished=caller.submit(()->dispatcher.execute(job,4000));
            assertNull(scheduler.sent.poll(200,TimeUnit.MILLISECONDS)); registry.register(worker("a"));
            Task task=scheduler.sent.poll(2,TimeUnit.SECONDS); assertNotNull(task);
            dispatcher.result(result(task,"a",false)); assertFalse(finished.get(2,TimeUnit.SECONDS));
            assertEquals(JobStatus.FAILED,job.getStatus()); assertTrue(job.getResults().isEmpty());
        } finally { caller.shutdownNow(); registry.shutdown(); }
    }
    @Test void timeoutReleasesReservationAndPayload() throws Exception {
        WorkerRegistry registry=new WorkerRegistry(); CaptureScheduler scheduler=new CaptureScheduler(registry);
        try(JobDispatcher dispatcher=new JobDispatcher("master",scheduler)) {
            WorkerInfo w=worker("a"); registry.register(w); Job job=job();
            assertFalse(dispatcher.execute(job,300)); assertEquals(JobStatus.FAILED,job.getStatus());
            assertEquals(0,w.getActiveTasks()); assertTrue(job.getTasks().getFirst().getPayload().isEmpty());
        } finally { registry.shutdown(); }
    }
    @Test void repeatedTransportFailureStopsAfterThreeAttempts() {
        WorkerRegistry registry=new WorkerRegistry();
        TaskScheduler scheduler=new TaskScheduler(registry) {
            @Override public Message sendReservedTask(Task t,WorkerInfo w) throws java.io.IOException { throw new java.io.IOException("disconnected"); }
        };
        try(JobDispatcher dispatcher=new JobDispatcher("master",scheduler)) {
            registry.register(worker("a")); Job job=job(); assertFalse(dispatcher.execute(job,5000));
            assertEquals(3,job.getTasks().getFirst().getRetryCount());
        } finally { registry.shutdown(); }
    }
}
