package com.pbl4;

import com.pbl4.core.model.*;
import com.pbl4.core.network.SocketMessenger;
import com.pbl4.master.WorkerRegistry;
import com.pbl4.master.MasterServer;
import com.pbl4.master.scheduler.*;
import com.pbl4.worker.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LaptopSchedulingTest {
    @TempDir Path temporaryDirectory;

    private WorkerInfo info(String machine, int index, int limit) {
        WorkerInfo w = new WorkerInfo(machine + "-worker-" + index, "127.0.0.1", 5000 + index, 1);
        w.machineId = machine; w.machineName = machine; w.hostParallelism = limit; w.sessionId = w.getWorkerId();
        return w;
    }

    @SuppressWarnings("unchecked")
    @Test void snapshotUsesOnlineLaptopMetadataAfterConfigurationChanges() {
        MasterServer master = new MasterServer(5000, "127.0.0.1", temporaryDirectory);
        try {
            WorkerInfo old = info("a", 0, 2); master.getRegistry().register(old); old.setStatus(WorkerStatus.OFFLINE);
            WorkerInfo current = info("a", 1, 4); current.machineName = "Laptop A new"; master.getRegistry().register(current);
            List<Map<String,Object>> machines = (List<Map<String,Object>>) master.getClusterSnapshot().get("machines");
            assertEquals("Laptop A new", machines.get(0).get("machineName"));
            assertEquals(4, machines.get(0).get("hostParallelism"));
            assertEquals(1, machines.get(0).get("connectedWorkers"));
            assertEquals(1, machines.get(0).get("offlineWorkers"));
        } finally { master.stop(); }
    }

    @Test void hundredLogicalWorkersShareFiveAtomicLaptopSlots() throws Exception {
        WorkerRegistry registry = new WorkerRegistry(); ExecutorService callers = Executors.newFixedThreadPool(12);
        try {
            for (int i = 0; i < 50; i++) { registry.register(info("a", i, 2)); registry.register(info("b", i, 3)); }
            TaskScheduler scheduler = new TaskScheduler(registry);
            assertEquals(5, scheduler.effectiveCapacity());
            List<Future<WorkerInfo>> requests = new ArrayList<>();
            for (int i = 0; i < 100; i++) requests.add(callers.submit(scheduler::reserveWorker));
            List<WorkerInfo> reserved = new ArrayList<>();
            for (Future<WorkerInfo> request : requests) { WorkerInfo w = request.get(); if (w != null) reserved.add(w); }
            assertEquals(5, reserved.size());
            assertEquals(2, reserved.stream().filter(w -> w.machineId.equals("a")).count());
            assertEquals(3, reserved.stream().filter(w -> w.machineId.equals("b")).count());
            assertNull(scheduler.reserveWorker());
            for (WorkerInfo w : reserved) scheduler.release(w);
            assertEquals(0, scheduler.machineActive("a")); assertEquals(0, scheduler.machineActive("b"));
            assertNotNull(scheduler.reserveWorker());
        } finally { callers.shutdownNow(); registry.shutdown(); }
    }

    @Test void machineLoadAndResourceGatesUseLaptopIdentity() {
        WorkerRegistry registry = new WorkerRegistry();
        try {
            for (int i = 0; i < 5; i++) { registry.register(info("a", i, 2)); registry.register(info("b", i, 4)); }
            TaskScheduler scheduler = new TaskScheduler(registry);
            assertEquals("a", scheduler.reserveWorker().machineId);
            assertEquals("b", scheduler.reserveWorker().machineId);
            assertEquals("b", scheduler.reserveWorker().machineId);
            scheduler.setPolicy(LoadBalancingPolicy.RESOURCE_AWARE);
            WorkerInfo latest = registry.getWorker("a-worker-4"); latest.metrics = new ResourceMetrics(95.,20.,20.,0);
            latest.setLastHeartbeatTime(System.currentTimeMillis() + 1000);
            for (WorkerInfo w : registry.getAllWorkers()) if (w.machineId.equals("a") && w.getActiveTasks() == 0) {
                assertFalse(scheduler.eligible(w)); assertEquals("CPU_HIGH", w.admissionReason);
            }
        } finally { registry.shutdown(); }
    }

    @Test void workerRuntimeRejectsExcessWorkAcrossDifferentWorkerPorts() throws Exception {
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger results = new AtomicInteger();
        try (ProtocolReliabilityTest.Peer peer = new ProtocolReliabilityTest.Peer(request -> {
            Message response = new Message(MessageType.HEARTBEAT_ACK, "MASTER"); response.masterSession = "m";
            if (request.getType() == MessageType.REGISTER_WORKER) response.setType(MessageType.REGISTER_ACK);
            if (request.getType() == MessageType.TASK_RESULT) { results.incrementAndGet(); response.setType(MessageType.RESULT_ACK); }
            return response;
        }); WorkerRuntime runtime = new WorkerRuntime(1)) {
            WorkerConfig ca = config("a", peer.port()), cb = config("b", peer.port());
            WorkerNode a = new WorkerNode(ca, runtime) {
                @Override protected TaskResult executeTask(Task task) {
                    try { finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return TaskResult.success(task.getTaskId(), task.getJobId(), "a").addResult("value", 3);
                }
            };
            WorkerNode b = new WorkerNode(cb, runtime);
            try {
                a.start(); b.start(); LanLifecycleTest.await(() -> a.isRegistered() && b.isRegistered(), 3000);
                assertEquals(MessageType.TASK_ACCEPTED, send(a, ca, "first").getType());
                Message rejected = send(b, cb, "second");
                assertEquals(MessageType.TASK_REJECTED, rejected.getType()); assertEquals("HOST_CAPACITY", rejected.getContent());
                assertEquals(1, runtime.getRunningTasks());
                finish.countDown(); LanLifecycleTest.await(() -> results.get() >= 1 && runtime.getRunningTasks() == 0, 3000);
                assertEquals(MessageType.TASK_ACCEPTED, send(b, cb, "second").getType());
                LanLifecycleTest.await(() -> results.get() >= 2, 3000);
            } finally { finish.countDown(); a.stop(); b.stop(); }
        }
    }
    private WorkerConfig config(String id, int mp) throws Exception {
        WorkerConfig cfg = new WorkerConfig(id, "127.0.0.1", LanLifecycleTest.port(), "127.0.0.1", mp);
        cfg.setMachineId("shared"); cfg.setHostParallelism(1); cfg.setCapacity(1); return cfg;
    }
    private Message send(WorkerNode node, WorkerConfig config, String id) throws Exception {
        Task task = new Task(id, "job", TaskType.ARITHMETIC).addPayload("expression", "1+2");
        task.attemptId = id; task.masterSession = "m"; task.workerSession = node.getSessionId();
        return SocketMessenger.sendAndReceive("127.0.0.1", config.getPort(), Message.assignTask(config.getWorkerId(), task), 2000);
    }

    @Test void progressWeightsUnequalChunksByWorkInsteadOfTaskCount() throws Exception {
        WorkerRegistry registry = new WorkerRegistry(); ExecutorService caller = Executors.newSingleThreadExecutor();
        BlockingQueue<Task> sent = new LinkedBlockingQueue<>();
        WorkerInfo w = new WorkerInfo("w", "127.0.0.1", 1, 2); w.sessionId = "w"; registry.register(w);
        TaskScheduler scheduler = new TaskScheduler(registry) {
            @Override public Message sendReservedTask(Task task, WorkerInfo worker) { sent.add(task); return new Message(MessageType.TASK_ACCEPTED, "w"); }
        };
        try (JobDispatcher dispatcher = new JobDispatcher("m", scheduler)) {
            Job job = new Job("weighted", "weighted", TaskType.ARITHMETIC);
            Task small = new Task("small", "weighted", TaskType.ARITHMETIC), large = new Task("large", "weighted", TaskType.ARITHMETIC);
            small.workUnits = 1; large.workUnits = 3; job.setTasks(List.of(small, large));
            Future<Boolean> completed = caller.submit(() -> dispatcher.execute(job, 5000));
            Task first = sent.poll(2, TimeUnit.SECONDS), second = sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(first); assertNotNull(second);
            Task one = first.getTaskId().equals("small") ? first : second;
            Task three = first.getTaskId().equals("large") ? first : second;
            dispatcher.result(result(one));
            assertEquals(25., ((Number) dispatcher.getJobSnapshot("weighted").get("progressPercent")).doubleValue());
            dispatcher.result(result(three)); assertTrue(completed.get(2, TimeUnit.SECONDS));
        } finally { caller.shutdownNow(); registry.shutdown(); }
    }
    private TaskResult result(Task task) {
        TaskResult result = TaskResult.success(task.getTaskId(), task.getJobId(), "w");
        result.attemptId = task.attemptId; result.masterSession = task.masterSession; result.workerSession = task.workerSession;
        return result;
    }
}
