package com.pbl4.worker;

import com.pbl4.core.model.ResourceMetrics;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One resource budget for all logical Workers hosted by a laptop launcher. */
public final class WorkerRuntime implements AutoCloseable {
    public final int parallelism;
    final Semaphore slots;
    final ExecutorService compute, controls, contacts, results;
    final ScheduledExecutorService timer;
    private final ResourceSampler sampler = new ResourceSampler();
    private final AtomicBoolean closed = new AtomicBoolean();

    public WorkerRuntime(int parallelism) {
        if (parallelism < 1 || parallelism > 1024) throw new IllegalArgumentException("Invalid laptop parallelism");
        this.parallelism = parallelism;
        slots = new Semaphore(parallelism);
        compute = Executors.newFixedThreadPool(parallelism);
        // Incoming PING/ACK must never wait behind outgoing registrations or computation.
        controls = Executors.newFixedThreadPool(8);
        contacts = Executors.newFixedThreadPool(8);
        results = Executors.newFixedThreadPool(4);
        timer = Executors.newScheduledThreadPool(2);
        sampler.sample();
        timer.scheduleAtFixedRate(sampler::sample, 1, 1, TimeUnit.SECONDS);
    }

    ResourceMetrics metrics(int workerTasks) {
        ResourceMetrics result = sampler.current(workerTasks);
        result.hostRunningTasks = parallelism - slots.availablePermits();
        return result;
    }

    public int getRunningTasks() { return parallelism - slots.availablePermits(); }
    public void timerForStatus(Runnable reporter) { timer.scheduleWithFixedDelay(reporter, 0, 1, TimeUnit.SECONDS); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        timer.shutdownNow(); contacts.shutdownNow(); controls.shutdownNow(); results.shutdownNow(); compute.shutdownNow();
    }
}
