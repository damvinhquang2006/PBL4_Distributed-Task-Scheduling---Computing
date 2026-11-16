package com.pbl4.master.scheduler;

import com.pbl4.core.model.*;
import java.util.*;
import java.util.concurrent.*;

/** Owns task transitions. No network IO while holding the state lock. */
public class JobDispatcher implements AutoCloseable {
    public enum ResultDisposition { COMMITTED, STALE, RETRY }
    @FunctionalInterface public interface ResultCommitter { void commit(TaskResult result) throws java.io.IOException; }
    @FunctionalInterface public interface AttemptRecorder {
        void record(Task task, WorkerInfo worker) throws java.io.IOException;
        default void transition(Task task, WorkerInfo worker, String attemptId, String state, String reason) throws java.io.IOException { }
    }
    private static final class Attempt {
        final Job job; final Task task; final WorkerInfo worker; final String id;
        boolean cancelPending, requeueAfterCancel, rejected;
        String retryReason;
        long lastCancelSentNanos;
        Attempt(Job job, Task task, WorkerInfo worker, String id) { this.job = job; this.task = task; this.worker = worker; this.id = id; }
    }
    private static final class AttemptProgress {
        final long startedAtNanos = System.nanoTime();
        long changedAtNanos = startedAtNanos;
        int completed = -1;
    }
    private final String session;
    private final TaskScheduler scheduler;
    private final Map<String, Job> jobs = new HashMap<>();
    private final Map<String, Attempt> attempts = new HashMap<>();
    private final Map<String, AttemptProgress> progress = new HashMap<>();
    private final Deque<Task> queue = new ArrayDeque<>();
    private final List<Map<String, Object>> failoverEvents = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService deliveries = Executors.newFixedThreadPool(8);
    private final ScheduledExecutorService pump = Executors.newSingleThreadScheduledExecutor();
    private boolean closed;
    private volatile ResultCommitter resultCommitter = result -> {};
    private volatile AttemptRecorder attemptRecorder = (task, worker) -> {};
    private volatile long attemptTimeoutMs = 60_000;
    private volatile long attemptHardTimeoutMs = 180_000;
    private volatile long quarantineRestartAfterMs = 10_000;
    public JobDispatcher(String session, TaskScheduler scheduler) {
        this.session = session; this.scheduler = scheduler;
        pump.scheduleWithFixedDelay(this::dispatch, 0, 100, TimeUnit.MILLISECONDS);
    }
    public void setResultCommitter(ResultCommitter value) { resultCommitter = Objects.requireNonNull(value); }
    public void setAttemptRecorder(AttemptRecorder value) { attemptRecorder = Objects.requireNonNull(value); }
    public void setAttemptTimeoutMs(long value) {
        if (value < 5_000) throw new IllegalArgumentException("attempt timeout must be at least 5 seconds");
        attemptTimeoutMs = value;
    }
    public void setAttemptHardTimeoutMs(long value) {
        if (value < 5_000) throw new IllegalArgumentException("attempt hard timeout must be at least 5 seconds");
        attemptHardTimeoutMs = value;
    }
    public void setQuarantineRestartAfterMs(long value) {
        if (value < 1_000) throw new IllegalArgumentException("quarantine restart delay must be at least 1 second");
        quarantineRestartAfterMs = value;
    }
    public synchronized boolean execute(Job job, long timeoutMs) {
        if (closed || job.getTasks().isEmpty() || jobs.containsKey(job.getJobId())) {
            job.setStatus(JobStatus.FAILED); return false;
        }
        job.setStatus(JobStatus.RUNNING); job.setStartTime(System.currentTimeMillis());
        jobs.put(job.getJobId(), job);
        queue.addAll(job.getTasks().stream().filter(t -> !job.getResults().containsKey(t.getTaskId())).toList());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            while (!job.isComplete() && job.getStatus() == JobStatus.RUNNING && !closed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    fail(job, "Hết thời gian chờ Worker hoàn thành tác vụ. " + scheduler.describeAvailability());
                    break;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return !closed && job.getStatus() == JobStatus.RUNNING && job.isComplete();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); job.setStatus(JobStatus.FAILED); return false;
        } finally {
            if (closed) job.setStatus(JobStatus.FAILED);
            jobs.remove(job.getJobId()); queue.removeIf(t -> t.getJobId().equals(job.getJobId()));
            for (Attempt a : new ArrayList<>(attempts.values())) {
                if (a.job == job) {
                    cancelWorkerAsync(a);
                    finishAttempt(a, false);
                }
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
            expireAttempts();
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
                    progress.put(a.id, new AttemptProgress());
                }
                try { attemptRecorder.record(a.task, a.worker); }
                catch (java.io.IOException e) {
                    synchronized (this) { if (current(a)) retry(a, true, "Could not persist attempt ownership: " + e.getMessage()); }
                    continue;
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
                wire.workUnits = a.task.workUnits;
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
            synchronized (this) { if (current(a)) retryDelivery(a, e.getMessage()); }
        }
    }
    private boolean current(Attempt a) { return attempts.get(a.task.getTaskId()) == a && !a.cancelPending; }
    public synchronized void reportProgress(String workerId, String workerSession, String masterSession,
                                            String attemptId, int completed, int total) {
        if (!session.equals(masterSession) || attemptId == null || total < 1 || completed < 0) return;
        for (Attempt a : attempts.values()) {
            if (a.id.equals(attemptId) && a.worker.getWorkerId().equals(workerId)
                && Objects.equals(a.worker.sessionId, workerSession) && a.job.getStatus() == JobStatus.RUNNING) {
                AttemptProgress p = progress.computeIfAbsent(attemptId, ignored -> new AttemptProgress());
                int bounded = Math.min(completed, total);
                if (bounded > p.completed) {
                    boolean started = p.completed < 0;
                    p.completed = bounded; p.changedAtNanos = System.nanoTime();
                    if (started) persistAttemptState(a, "RUNNING", null);
                }
                return;
            }
        }
    }
    private synchronized void expireAttempts() {
        long now = System.nanoTime(), timeout = TimeUnit.MILLISECONDS.toNanos(attemptTimeoutMs);
        long hardTimeout = TimeUnit.MILLISECONDS.toNanos(attemptHardTimeoutMs);
        for (Attempt a : new ArrayList<>(attempts.values())) {
            if (a.cancelPending) {
                if (now - a.lastCancelSentNanos >= TimeUnit.SECONDS.toNanos(1)) requestCancellation(a);
                long quarantineAge = now - a.worker.quarantineSinceNanos;
                long restartDelay = TimeUnit.MILLISECONDS.toNanos(quarantineRestartAfterMs);
                if (a.worker.supervised && a.worker.quarantined && quarantineAge >= restartDelay
                    && (!a.worker.groupRestartRequested || System.currentTimeMillis() - a.worker.groupRestartAttemptedAtMillis >= 5_000))
                    requestGroupRestart(a.worker);
                continue;
            }
            AttemptProgress p = progress.get(a.id);
            if (p != null && now - p.startedAtNanos >= hardTimeout)
                retry(a, false, "Attempt exceeded hard runtime limit " + attemptHardTimeoutMs + " ms");
            else if (p != null && now - p.changedAtNanos >= timeout)
                retry(a, false, "Task made no progress for " + attemptTimeoutMs + " ms");
        }
    }
    private void retryDelivery(Attempt a, String detail) {
        if (attempts.get(a.task.getTaskId()) != a || a.cancelPending) return;
        a.rejected = false;
        a.retryReason = "KhÃ´ng giao Ä‘Æ°á»£c tÃ¡c vá»¥ tá»›i Worker [" + a.worker.getWorkerId() + "] táº¡i " + a.worker.getHost() + ":" + a.worker.getPort();
        if (detail != null) a.retryReason += ": " + detail;
        persistAttemptState(a, "FAILED", detail);
        a.worker.unavailableUntil = System.currentTimeMillis() + 1000;
        finishAttempt(a, a.job.getStatus() == JobStatus.RUNNING);
    }

    private void cancelWorkerAsync(Attempt a) {
        deliveries.submit(() -> {
            try {
                Message request = new Message(MessageType.CANCEL_TASK, "MASTER");
                request.masterSession = session;
                request.workerSession = a.worker.sessionId;
                request.setContent(a.id + "/" + a.job.getJobId());
                com.pbl4.core.network.SocketMessenger.sendAndReceive(
                    a.worker.getHost(), a.worker.getPort(), request, 2500);
            } catch (Exception ignored) { }
        });
    }

    private void retry(Attempt a, boolean rejected) {
        retry(a, rejected, null);
    }
    private void retry(Attempt a, boolean rejected, String detail) {
        if (attempts.get(a.task.getTaskId()) != a || a.cancelPending) return;
        a.rejected = rejected;
        a.retryReason = rejected ? "Worker rejected the attempt" : "Attempt must stop before retry";
        if (detail != null) a.retryReason += ": " + detail;
        if (rejected || a.worker.getStatus() == WorkerStatus.OFFLINE) {
            persistAttemptState(a, "FAILED", detail);
            finishAttempt(a, a.job.getStatus() == JobStatus.RUNNING);
            return;
        }
        a.cancelPending = true;
        a.requeueAfterCancel = a.job.getStatus() == JobStatus.RUNNING;
        persistAttemptState(a, a.requeueAfterCancel ? "EXPIRED" : "CANCEL_REQUESTED", detail);
        a.worker.unavailableUntil = System.currentTimeMillis() + 1000;
        requestCancellation(a);
    }

    private void requestCancellation(Attempt a) {
        if (!a.cancelPending || attempts.get(a.task.getTaskId()) != a) return;
        a.lastCancelSentNanos = System.nanoTime();
        try { deliveries.submit(() -> sendCancellation(a)); }
        catch (RejectedExecutionException ignored) { }
    }

    private void persistAttemptState(Attempt attempt, String state, String reason) {
        try { attemptRecorder.transition(attempt.task, attempt.worker, attempt.id, state, reason); }
        catch (java.io.IOException e) { System.err.println("[Dispatcher] Could not persist attempt state " + attempt.id + ": " + e.getMessage()); }
    }

    private void sendCancellation(Attempt a) {
        boolean stopped = false;
        try {
            Message request = new Message(MessageType.CANCEL_TASK, "MASTER"); request.masterSession = session;
            request.workerSession = a.worker.sessionId; request.setContent(a.id + "/" + a.job.getJobId());
            Message ack = com.pbl4.core.network.SocketMessenger.sendAndReceive(
                a.worker.getHost(), a.worker.getPort(), request, 2500);
            stopped = ack != null && ack.protocolVersion == Message.CURRENT_VERSION
                && ack.getType() == MessageType.CANCEL_ACK && "STOPPED".equals(ack.getContent());
        } catch (Exception e) {
            System.err.println("[Dispatcher] Cancel pending for " + a.task.getTaskId() + " on " + a.worker.getWorkerId() + ": " + e.getMessage());
        }
        synchronized (this) {
            if (attempts.get(a.task.getTaskId()) != a || !a.cancelPending) return;
            if (stopped) finishAttempt(a, a.requeueAfterCancel);
            else scheduler.quarantineGroup(a.worker, true);
        }
    }

    private void requestGroupRestart(WorkerInfo worker) {
        scheduler.markGroupRestartRequested(worker);
        try { deliveries.submit(() -> {
            boolean acknowledged = false;
            try {
                Message request = new Message(MessageType.RESTART_PROCESS_GROUP, "MASTER");
                request.masterSession = session; request.workerSession = worker.sessionId;
                Message response = com.pbl4.core.network.SocketMessenger.sendAndReceive(
                    worker.getHost(), worker.getPort(), request, 2500);
                acknowledged = response != null && response.protocolVersion == Message.CURRENT_VERSION
                    && response.getType() == MessageType.RESTART_ACK;
                if (acknowledged) System.err.printf("[Dispatcher] Restarting quarantined process group %s on %s%n",
                    worker.processGroupId, worker.machineId);
            } catch (Exception e) { System.err.println("[Dispatcher] Process-group restart request failed: " + e.getMessage()); }
            if (!acknowledged) synchronized (this) { scheduler.clearGroupRestartRequested(worker); }
        }); } catch (RejectedExecutionException ignored) { scheduler.clearGroupRestartRequested(worker); }
    }

    private void finishAttempt(Attempt a, boolean allowRequeue) {
        if (attempts.remove(a.task.getTaskId()) != a) return;
        scheduler.release(a.worker); progress.remove(a.id);
        boolean groupStillQuarantined = attempts.values().stream().anyMatch(other -> other.cancelPending && sameGroup(other.worker, a.worker));
        if (!groupStillQuarantined && !a.worker.groupRestartRequested) scheduler.quarantineGroup(a.worker, false);
        if (a.cancelPending && a.job.getStatus() == JobStatus.RUNNING) a.worker.unavailableUntil = System.currentTimeMillis() + 1000;
        if (a.rejected) {
            if (a.task.getRetryCount() < 5) {
                a.task.undoRetry(); // No computation was accepted; deadline still bounds the job.
            }
        }
        if (!allowRequeue) { notifyAll(); return; }
        String reason = "Attempt tại Worker [" + a.worker.getWorkerId() + "] đã dừng; có thể giao lại";
        if (a.retryReason != null) reason += ": " + a.retryReason;
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("timestamp", System.currentTimeMillis());
        evt.put("workerId", a.worker.getWorkerId());
        evt.put("taskId", a.task.getTaskId());
        evt.put("jobId", a.job.getJobId());
        evt.put("retryCount", a.task.getRetryCount());
        evt.put("reason", reason);
        evt.put("message", String.format("Sự cố tại %s: %s -> Hàng đợi tự động REQUEUE [%s] sang Worker tiếp theo đang rảnh!",
                a.worker.getWorkerId(), reason, a.task.getTaskId()));
        failoverEvents.add(evt);
        if (failoverEvents.size() > 100) failoverEvents.remove(0);

        if (a.task.getRetryCount() >= 3) fail(a.job, "Đã hết ba lần thử giao tác vụ. " + reason);
        else if (jobs.containsKey(a.job.getJobId()) && a.job.getStatus() == JobStatus.RUNNING) {
            queue.addLast(a.task);
            System.out.println("[Dispatcher] REQUEUE " + a.task.getTaskId());
        }
        notifyAll();
    }
    private void fail(Job job, String reason) {
        job.setFailureReason(reason);
        job.setStatus(JobStatus.FAILED); System.err.println("[Job] " + job.getJobId() + ": " + reason); notifyAll();
    }
    private static boolean sameGroup(WorkerInfo left, WorkerInfo right) {
        String leftMachine = left.machineId == null ? left.getWorkerId() : left.machineId;
        String rightMachine = right.machineId == null ? right.getWorkerId() : right.machineId;
        String leftGroup = left.processGroupId == null || left.processGroupId.isBlank() ? "default" : left.processGroupId;
        String rightGroup = right.processGroupId == null || right.processGroupId.isBlank() ? "default" : right.processGroupId;
        return leftMachine.equals(rightMachine) && leftGroup.equals(rightGroup);
    }
    public synchronized void workerFailed(WorkerInfo worker) {
        for (Attempt a : new ArrayList<>(attempts.values())) if (a.worker == worker) {
            if (a.cancelPending) finishAttempt(a, a.requeueAfterCancel && a.job.getStatus() == JobStatus.RUNNING);
            else retry(a, false, "Worker went OFFLINE");
        }
    }
    public synchronized ResultDisposition result(TaskResult result) {
        if (result == null || !session.equals(result.masterSession)) return ResultDisposition.STALE;
        Attempt a = attempts.get(result.getTaskId());
        if (a == null || !Objects.equals(a.id, result.attemptId)
            || !Objects.equals(a.worker.sessionId, result.workerSession)
            || !a.worker.getWorkerId().equals(result.getWorkerId())
            || !a.job.getJobId().equals(result.getJobId()) || a.job.getStatus() != JobStatus.RUNNING || a.cancelPending) return ResultDisposition.STALE;
        try { resultCommitter.commit(result); }
        catch (java.io.IOException e) { System.err.println("[Dispatcher] Result persistence failed; requesting retry: " + e.getMessage()); return ResultDisposition.RETRY; }
        attempts.remove(a.task.getTaskId()); scheduler.release(a.worker);
        progress.remove(a.id);
        if (!result.isSuccess()) fail(a.job, result.getErrorMessage());
        else {
            a.worker.incrementCompletedTasks(); a.job.addResult(result);
            System.out.printf("[Result] %s <- %s (%d/%d)%n", result.getTaskId(), result.getWorkerId(),
                a.job.getResults().size(), a.job.getTasks().size());
        }
        notifyAll();
        return ResultDisposition.COMMITTED;
    }

    public synchronized Map<String, Object> getJobSnapshot(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) return null;
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("jobId", job.getJobId());
        snap.put("title", job.getTitle());
        snap.put("status", job.getStatus().name());
        int total = job.getTasks().size();
        int completed = job.getResults().size();
        snap.put("totalTasks", total);
        snap.put("completedTasks", completed);
        long totalUnits = job.getTasks().stream().mapToLong(t -> Math.max(1, t.workUnits)).sum();
        long completedUnits = job.getTasks().stream().filter(t -> job.getResults().containsKey(t.getTaskId())).mapToLong(t -> Math.max(1, t.workUnits)).sum();
        snap.put("totalUnits", totalUnits); snap.put("completedUnits", completedUnits);
        double pct = totalUnits > 0 ? completedUnits * 100.0 / totalUnits : 0.0;
        snap.put("progressPercent", Math.round(pct * 10.0) / 10.0);
        snap.put("queueSize", queue.stream().filter(t -> t.getJobId().equals(jobId)).count());
        snap.put("inflightCount", attempts.values().stream().filter(a -> a.job == job).count());
        snap.put("startTime", job.getStartTime());
        snap.put("durationMs", job.getDurationMs());

        Map<String, String> activeAssignments = new LinkedHashMap<>();
        for (Attempt a : attempts.values()) {
            if (a.job == job) {
                activeAssignments.put(a.worker.getWorkerId(), a.task.getTaskId());
            }
        }
        snap.put("activeAssignments", activeAssignments);
        Map<String, Long> workerCompleted = new LinkedHashMap<>();
        job.getResults().values().forEach(r -> workerCompleted.merge(r.getWorkerId(), 1L, Long::sum));
        snap.put("workerCompletedTasks", workerCompleted);
        return snap;
    }

    public synchronized Map<String, List<String>> getWorkerActivity() {
        Map<String, List<String>> result = new HashMap<>();
        for (Attempt attempt : attempts.values()) result.computeIfAbsent(attempt.worker.getWorkerId(), id -> new ArrayList<>()).add(attempt.task.getTaskId());
        return result;
    }

    public List<Map<String, Object>> getRecentFailoverEvents() {
        synchronized (failoverEvents) {
            return new ArrayList<>(failoverEvents);
        }
    }

    public synchronized boolean cancel(String jobId) {
        Job job = jobs.get(jobId);
        if (job != null) {
            job.setStatus(JobStatus.CANCELLED);
            queue.removeIf(task -> task.getJobId().equals(jobId));
            for (Attempt a : new ArrayList<>(attempts.values())) if (a.job == job) retry(a, false, "Job cancelled");
            notifyAll();
            return true;
        }
        return false;
    }
    @Override public synchronized void close() {
        closed = true; pump.shutdownNow(); deliveries.shutdownNow(); notifyAll();
    }
}
