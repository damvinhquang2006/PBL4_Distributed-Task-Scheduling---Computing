package com.pbl4.master.scheduler;

import com.pbl4.core.model.*;
import java.util.*;
import java.util.concurrent.*;

/** Owns task transitions. No network IO while holding the state lock. */
public class JobDispatcher implements AutoCloseable {
    private record Attempt(Job job, Task task, WorkerInfo worker, String id) {}
    private final String session;
    private final TaskScheduler scheduler;
    private final Map<String, Job> jobs = new HashMap<>();
    private final Map<String, Attempt> attempts = new HashMap<>();
    private final Deque<Task> queue = new ArrayDeque<>();
    private final ExecutorService deliveries = Executors.newFixedThreadPool(8);
    private final ScheduledExecutorService pump = Executors.newSingleThreadScheduledExecutor();
    private boolean closed;
    public JobDispatcher(String session, TaskScheduler scheduler) {
        this.session = session; this.scheduler = scheduler;
        pump.scheduleWithFixedDelay(this::dispatch, 0, 100, TimeUnit.MILLISECONDS);
    }
    public synchronized boolean execute(Job job, long timeoutMs) {
        if (closed || job.getTasks().isEmpty() || jobs.containsKey(job.getJobId())) {
            job.setStatus(JobStatus.FAILED); return false;
        }
        job.setStatus(JobStatus.RUNNING); job.setStartTime(System.currentTimeMillis());
        jobs.put(job.getJobId(), job); queue.addAll(job.getTasks());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            while (!job.isComplete() && job.getStatus() == JobStatus.RUNNING && !closed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { job.setStatus(JobStatus.FAILED); break; }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return !closed && job.getStatus() == JobStatus.RUNNING && job.isComplete();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); job.setStatus(JobStatus.FAILED); return false;
        } finally {
            if (closed) job.setStatus(JobStatus.FAILED);
            jobs.remove(job.getJobId()); queue.removeIf(t -> t.getJobId().equals(job.getJobId()));
            for (Attempt a : new ArrayList<>(attempts.values())) {
                if (a.job == job) { attempts.remove(a.task.getTaskId()); scheduler.release(a.worker); }
            }
            // Payload factories hold input matrices: release them at every terminal path.
            for (Task task : job.getTasks()) task.releasePayload();
            job.setEndTime(System.currentTimeMillis());
            if (job.getStatus() == JobStatus.FAILED) {
                job.getResults().clear(); System.err.println("[Job] FAILED " + job.getJobId());
            }
        }
    }
    private void dispatch() {
        try {
            while (true) {
                Attempt a;
                synchronized (this) {
                    if (closed || queue.isEmpty()) return;
                    Task task = queue.peekFirst(); Job job = jobs.get(task.getJobId());
                    if (job == null || job.getStatus() != JobStatus.RUNNING) { queue.removeFirst(); continue; }
                    WorkerInfo worker = scheduler.reserveWorker();
                    if (worker == null) return;
                    queue.removeFirst();
                    task.attemptId = UUID.randomUUID().toString(); task.masterSession = session;
                    task.workerSession = worker.sessionId; task.setWorkerAssigned(worker.getWorkerId());
                    task.incrementRetryCount();
                    a = new Attempt(job, task, worker, task.attemptId);
                    attempts.put(task.getTaskId(), a);
                }
                deliveries.submit(() -> deliver(a));
            }
        } catch (RejectedExecutionException ignored) {
            // Shutdown owns cleanup.
        } catch (RuntimeException e) { System.err.println("[Dispatcher] " + e); }
    }
    private void deliver(Attempt a) {
        Task wire;
        synchronized (this) {
            if (!current(a)) return;
            try {
                a.task.materializePayload();
                wire = new Task(a.task.getTaskId(), a.task.getJobId(), a.task.getTaskType());
                wire.setPayload(new HashMap<>(a.task.getPayload())); wire.attemptId = a.id;
                wire.masterSession = session; wire.workerSession = a.worker.sessionId;
                a.task.clearMaterializedPayload();
            } catch (RuntimeException e) { fail(a.job, "Invalid payload: " + e.getMessage()); return; }
        }
        try {
            Message ack = scheduler.sendReservedTask(wire, a.worker);
            synchronized (this) {
                if (!current(a)) return;
                if (ack.metrics != null) a.worker.metrics = ack.metrics;
                if (ack.getType() != MessageType.TASK_ACCEPTED) retry(a, true);
            }
        } catch (Exception e) {
            synchronized (this) { if (current(a)) retry(a, false); }
        }
    }
    private boolean current(Attempt a) { return attempts.get(a.task.getTaskId()) == a; }
    private void retry(Attempt a, boolean rejected) {
        attempts.remove(a.task.getTaskId()); scheduler.release(a.worker);
        a.worker.unavailableUntil = System.currentTimeMillis() + 1000;
        if (rejected) a.task.undoRetry(); // No computation was accepted; deadline still bounds the job.
        if (a.task.getRetryCount() >= 3) fail(a.job, "Three delivery/execution attempts exhausted");
        else if (jobs.containsKey(a.job.getJobId()) && a.job.getStatus() == JobStatus.RUNNING) {
            queue.addLast(a.task);
            System.out.println("[Dispatcher] REQUEUE " + a.task.getTaskId());
        }
        notifyAll();
    }
    private void fail(Job job, String reason) {
        job.setStatus(JobStatus.FAILED); System.err.println("[Job] " + job.getJobId() + ": " + reason); notifyAll();
    }
    public synchronized void workerFailed(WorkerInfo worker) {
        for (Attempt a : new ArrayList<>(attempts.values())) if (a.worker == worker) retry(a, false);
    }
    public synchronized void result(TaskResult result) {
        if (result == null || !session.equals(result.masterSession)) return;
        Attempt a = attempts.get(result.getTaskId());
        if (a == null || !Objects.equals(a.id, result.attemptId)
            || !Objects.equals(a.worker.sessionId, result.workerSession)
            || !a.worker.getWorkerId().equals(result.getWorkerId())
            || !a.job.getJobId().equals(result.getJobId()) || a.job.getStatus() != JobStatus.RUNNING) return;
        attempts.remove(a.task.getTaskId()); scheduler.release(a.worker);
        if (!result.isSuccess()) fail(a.job, result.getErrorMessage());
        else {
            a.worker.incrementCompletedTasks(); a.job.addResult(result);
            System.out.printf("[Result] %s <- %s (%d/%d)%n", result.getTaskId(), result.getWorkerId(),
                a.job.getResults().size(), a.job.getTasks().size());
        }
        notifyAll();
    }
    @Override public synchronized void close() {
        closed = true; pump.shutdownNow(); deliveries.shutdownNow(); notifyAll();
    }
}
