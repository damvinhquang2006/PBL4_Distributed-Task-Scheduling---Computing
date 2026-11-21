package com.pbl4.master;

import com.pbl4.computing.matrix.Matrix;
import com.pbl4.core.model.Task;
import com.pbl4.core.model.TaskResult;
import com.pbl4.core.model.WorkerInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasterStateStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void opensNewEmptyJournalOnWindowsCompatibleOptions() throws IOException {
        Path dataDirectory = temporaryDirectory.resolve("fresh-master");

        try (MasterStateStore store = new MasterStateStore(dataDirectory)) {
            assertFalse(store.hasJob("missing"));
            assertTrue(Files.exists(dataDirectory.resolve("master-state.jsonl")));
        }
    }

    @Test
    void reopensJournalThatAlreadyEndsWithNewline() throws IOException {
        Path dataDirectory = temporaryDirectory.resolve("existing-master");
        try (MasterStateStore store = new MasterStateStore(dataDirectory)) {
            store.createJob("job-1", Map.of("operation", "test"), 1L, 2L, 1, null, null);
        }

        try (MasterStateStore reopened = new MasterStateStore(dataDirectory)) {
            assertTrue(reopened.hasJob("job-1"));
        }
    }

    @Test
    void repairsMissingFinalNewlineAndContinuesAppending() throws IOException {
        Path dataDirectory = temporaryDirectory.resolve("torn-boundary-master");
        Path journal = dataDirectory.resolve("master-state.jsonl");
        try (MasterStateStore store = new MasterStateStore(dataDirectory)) {
            store.createJob("job-1", Map.of("operation", "test"), 1L, 2L, 1, null, null);
        }

        byte[] contents = Files.readAllBytes(journal);
        assertTrue(contents.length > 0 && contents[contents.length - 1] == (byte) '\n');
        Files.write(journal, java.util.Arrays.copyOf(contents, contents.length - 1),
            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

        try (MasterStateStore reopened = new MasterStateStore(dataDirectory)) {
            assertTrue(reopened.hasJob("job-1"));
            reopened.createJob("job-2", Map.of("operation", "test"), 3L, 4L, 1, null, null);
        }

        byte[] repairedContents = Files.readAllBytes(journal);
        assertTrue(repairedContents.length > 0 && repairedContents[repairedContents.length - 1] == (byte) '\n');
        try (MasterStateStore reopened = new MasterStateStore(dataDirectory)) {
            assertTrue(reopened.hasJob("job-1"));
            assertTrue(reopened.hasJob("job-2"));
        }
    }

    @Test
    void restoresCompletedJobAndWorkerCountsWhenMasterRestarts() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("completed-job-master");
        String jobId = "restored-job";
        Map<String, Object> genA = Map.of("generator", "constant-v1", "val", 1.0);
        Map<String, Object> request = Map.of(
            "operation", "scalar", "rowsA", 1, "colsA", 1, "rowsB", 1, "colsB", 1,
            "scalar", 2.0, "genA", genA, "genB", Map.of(), "taskCount", 1
        );
        MatrixJobPlan plan = new MatrixJobPlan("scalar", 1, 1, 1, 1, genA, Map.of(), 2.0, null, null);
        Task task = plan.tasks(jobId, 1).get(0);

        try (MasterStateStore store = new MasterStateStore(dataDirectory)) {
            store.createJob(jobId, request, 10L, 20L, 1, null, null);
            TaskResult result = TaskResult.success(task.getTaskId(), jobId, "worker-a")
                .addResult("startRow", 0)
                .addResult("sliceResult", new double[][]{{2.0}});
            store.recordResult(jobId, result);
            store.complete(jobId, Map.of("distributedTimeMs", 5L, "workerIds", List.of("worker-a")));
        }

        MasterServer restored = new MasterServer(0, "127.0.0.1", dataDirectory);
        try {
            Map<String, Object> progress = restored.getJobProgress(jobId);
            assertTrue(progress != null);
            assertTrue("COMPLETED".equals(progress.get("status")));
            assertEquals(2.0, ((Matrix) progress.get("result")).getData()[0][0], 1e-9);
        } finally {
            restored.stop();
        }
    }

    @Test
    void persistsWorkerAliasesInterleavedWithJobsAndRestoresAfterMasterRestart() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("alias-master");
        String jobId = "alias-job";
        Map<String, Object> genA = Map.of("generator", "constant-v1", "val", 1.0);
        Map<String, Object> request = Map.of(
            "operation", "scalar", "rowsA", 1, "colsA", 1, "rowsB", 1, "colsB", 1,
            "scalar", 3.0, "genA", genA, "genB", Map.of(), "taskCount", 1
        );
        MatrixJobPlan plan = new MatrixJobPlan("scalar", 1, 1, 1, 1, genA, Map.of(), 3.0, null, null);
        Task task = plan.tasks(jobId, 1).get(0);

        try (MasterStateStore store = new MasterStateStore(dataDirectory)) {
            store.recordWorkerAlias("worker-a", "Máy A – Chính");
            store.createJob(jobId, request, 10L, 20L, 1, null, null);
            store.recordWorkerAlias("worker-b", "Worker B");
            TaskResult result = TaskResult.success(task.getTaskId(), jobId, "worker-a")
                .addResult("startRow", 0)
                .addResult("sliceResult", new double[][]{{3.0}});
            store.recordResult(jobId, result);
            store.recordWorkerAlias("worker-a", "Máy A – Đã đổi tên");
            store.complete(jobId, Map.of("distributedTimeMs", 5L, "workerIds", List.of("worker-a")));

            assertThrows(IOException.class, () -> store.recordWorkerAlias("worker-a", "   "));
            assertThrows(IOException.class, () -> store.recordWorkerAlias("worker-a", "a".repeat(65)));
            assertThrows(IOException.class, () -> store.recordWorkerAlias("worker-a", "bad\u0007name"));
        }

        MasterServer restored = new MasterServer(0, "127.0.0.1", dataDirectory);
        try {
            WorkerInfo reconnected = new WorkerInfo("worker-a", "127.0.0.1", 5111, 1);
            reconnected.sessionId = "sess-new";
            reconnected.machineId = "laptop-a";
            reconnected.machineName = "Laptop A";
            reconnected.hostParallelism = 2;
            reconnected.displayName = "worker-a";
            restored.getRegistry().register(reconnected);

            assertEquals("Máy A – Đã đổi tên", restored.getRegistry().getWorker("worker-a").displayName);
            assertEquals("Worker B", restored.resolveWorkerDisplayName("worker-b"));

            Map<String, Object> progress = restored.getJobProgress(jobId);
            assertEquals("COMPLETED", progress.get("status"));
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) progress.get("resultSummary");
            @SuppressWarnings("unchecked")
            Map<String, String> names = (Map<String, String>) summary.get("workerDisplayNames");
            assertEquals("Máy A – Đã đổi tên", names.get("worker-a"));
        } finally {
            restored.stop();
        }
    }
}
