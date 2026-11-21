package com.pbl4.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pbl4.core.model.TaskResult;
import com.pbl4.core.network.JsonUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.util.*;

/** Local append-only journal for accepted asynchronous matrix jobs and committed task results. */
final class MasterStateStore implements AutoCloseable {
    static final class StoredJob {
        final String jobId;
        final JsonNode request;
        final long startedAt;
        final long deadlineAt;
        final int totalTasks;
        String idempotencyKey;
        String requestHash;
        String state = "RUNNING";
        String error;
        JsonNode resultSummary;
        final Map<String, TaskResult> results = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> attempts = new LinkedHashMap<>();

        StoredJob(String jobId, JsonNode request, long startedAt, long deadlineAt, int totalTasks) {
            this.jobId = jobId; this.request = request; this.startedAt = startedAt; this.deadlineAt = deadlineAt; this.totalTasks = totalTasks;
        }
    }

    private final Path journal;
    private final Path directory;
    private final Path artifacts;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final FileChannel channel;
    private final Map<String, StoredJob> jobs = new LinkedHashMap<>();
    private final Map<String, String> idempotencyKeys = new HashMap<>();
    private final Map<String, String> requestHashes = new HashMap<>();
    private final Map<String, String> workerAliases = new LinkedHashMap<>();
    private boolean closed;

    MasterStateStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.directory = directory.toAbsolutePath().normalize();
        this.artifacts = this.directory.resolve("artifacts");
        Path lockPath = this.directory.resolve("master.lock");
        lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (OverlappingFileLockException e) { acquired = null; }
        if (acquired == null) {
            lockChannel.close();
            throw new IOException("Another Master owns the data directory " + this.directory);
        }
        lock = acquired;
        journal = this.directory.resolve("master-state.jsonl");
        FileChannel openedChannel = null;
        try {
            replay();
            channel = openedChannel = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            ensureLineBoundary();
            expirePreviousAttempts();
        } catch (IOException | RuntimeException e) {
            if (openedChannel != null) {
                try { openedChannel.close(); } catch (IOException closeError) { e.addSuppressed(closeError); }
            }
            try { lock.release(); } catch (IOException releaseError) { e.addSuppressed(releaseError); }
            try { lockChannel.close(); } catch (IOException closeError) { e.addSuppressed(closeError); }
            throw e;
        }
    }

    synchronized Collection<StoredJob> jobs() { return List.copyOf(jobs.values()); }
    synchronized boolean hasJob(String id) { return jobs.containsKey(id); }
    synchronized Map<String, String> aliases() { return Map.copyOf(workerAliases); }

    synchronized void recordWorkerAlias(String workerId, String displayName) throws IOException {
        if (workerId == null || workerId.isBlank()) throw new IOException("workerId is required");
        String normalized = validateDisplayName(displayName);
        ObjectNode event = JsonUtils.getMapper().createObjectNode();
        event.put("schemaVersion", 1);
        event.put("type", "WORKER_ALIAS");
        event.put("workerId", workerId);
        event.put("displayName", normalized);
        event.put("updatedAt", System.currentTimeMillis());
        append(event);
        workerAliases.put(workerId, normalized);
    }

    static String validateDisplayName(String rawName) throws IOException {
        if (rawName == null) throw new IOException("displayName is required");
        String displayName = rawName.strip();
        if (displayName.isEmpty() || displayName.length() > 64 || displayName.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("displayName must contain 1-64 visible characters");
        }
        return displayName;
    }

    synchronized StoredJob findIdempotent(String key, String requestHash) throws IOException {
        if (key == null || key.isBlank()) return null;
        String id = idempotencyKeys.get(key);
        if (id == null) return null;
        if (!Objects.equals(requestHashes.get(key), requestHash)) throw new IOException("Idempotency-Key was already used with different input");
        return jobs.get(id);
    }

    synchronized void createJob(String id, Map<String, Object> request, long startedAt, long deadlineAt, int totalTasks,
                                String idempotencyKey, String requestHash) throws IOException {
        if (id == null || request == null || totalTasks < 1 || deadlineAt < startedAt)
            throw new IOException("Invalid durable Job record");
        if (jobs.containsKey(id)) throw new IOException("Duplicate persisted job ID " + id);
        if (idempotencyKey != null && !idempotencyKey.isBlank() && idempotencyKeys.containsKey(idempotencyKey))
            throw new IOException("Idempotency-Key is already registered");
        String inputArtifact = writeArtifact("inputs/" + safeSegment(id) + ".json",
            JsonUtils.getMapper().writeValueAsBytes(request));
        ObjectNode event = event("CREATE", id);
        event.put("startedAt", startedAt); event.put("deadlineAt", deadlineAt); event.put("totalTasks", totalTasks);
        event.put("inputArtifact", inputArtifact); event.put("inputSha256", checksum(Files.readAllBytes(artifacts.resolve(inputArtifact))));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) { event.put("idempotencyKey", idempotencyKey); event.put("requestHash", requestHash); }
        append(event);
        StoredJob job = new StoredJob(id, JsonUtils.getMapper().valueToTree(request), startedAt, deadlineAt, totalTasks);
        job.idempotencyKey = idempotencyKey; job.requestHash = requestHash;
        jobs.put(id, job);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyKeys.put(idempotencyKey, id); requestHashes.put(idempotencyKey, requestHash);
        }
    }

    synchronized void recordResult(String jobId, TaskResult result) throws IOException {
        if (result == null || !result.isSuccess() || !Objects.equals(jobId, result.getJobId())
            || result.getTaskId() == null || result.getTaskId().isBlank()) throw new IOException("Invalid successful TaskResult");
        StoredJob job = required(jobId);
        if (job.results.containsKey(result.getTaskId())) return;
        String artifact = writeArtifact("results/" + safeSegment(jobId) + "/" + safeSegment(result.getTaskId()) + ".json",
            JsonUtils.getMapper().writeValueAsBytes(result));
        ObjectNode event = event("RESULT", jobId); event.put("taskId", result.getTaskId());
        if (result.attemptId != null) event.put("attemptId", result.attemptId);
        event.put("attemptState", "SUCCEEDED");
        event.put("artifact", artifact); event.put("sha256", checksum(Files.readAllBytes(artifacts.resolve(artifact)))); append(event);
        job.results.put(result.getTaskId(), result);
        job.attempts.remove(result.getTaskId());
    }

    synchronized void recordAttempt(String jobId, String taskId, String attemptId, String workerId,
                                    String workerSession, String masterEpoch, long assignedAt) throws IOException {
        StoredJob job = required(jobId);
        if (taskId == null || taskId.isBlank() || attemptId == null || attemptId.isBlank()
            || workerId == null || workerId.isBlank() || workerSession == null || workerSession.isBlank()
            || masterEpoch == null || masterEpoch.isBlank()) throw new IOException("Invalid durable attempt record");
        if (job.results.containsKey(taskId)) return;
        ObjectNode event = event("ATTEMPT", jobId); event.put("taskId", taskId); event.put("attemptId", attemptId);
        event.put("workerId", workerId); event.put("workerSession", workerSession); event.put("masterEpoch", masterEpoch);
        event.put("assignedAt", assignedAt); append(event);
        Map<String, Object> attempt = new LinkedHashMap<>(); attempt.put("attemptId", attemptId);
        attempt.put("workerId", workerId); attempt.put("workerSession", workerSession); attempt.put("masterEpoch", masterEpoch);
        attempt.put("assignedAt", assignedAt); attempt.put("state", "ASSIGNED"); job.attempts.put(taskId, attempt);
    }

    synchronized void updateAttemptState(String jobId, String taskId, String attemptId, String state,
                                         long changedAt, String reason) throws IOException {
        StoredJob job = required(jobId);
        Map<String, Object> current = job.attempts.get(taskId);
        if (current == null || !Objects.equals(current.get("attemptId"), attemptId)) return;
        if (Objects.equals(current.get("state"), state)) return;
        ObjectNode event = event("ATTEMPT_STATE", jobId); event.put("taskId", taskId); event.put("attemptId", attemptId);
        event.put("state", state); event.put("changedAt", changedAt); if (reason != null) event.put("reason", reason); append(event);
        current.put("state", state); current.put("changedAt", changedAt); if (reason != null) current.put("reason", reason);
    }

    synchronized void updateState(String jobId, String state, String error) throws IOException {
        StoredJob job = required(jobId);
        ObjectNode event = event("STATE", jobId); event.put("state", state);
        if (error != null) event.put("error", error); append(event);
        job.state = state; job.error = error;
    }

    synchronized void complete(String jobId, Map<String, Object> summary) throws IOException {
        StoredJob job = required(jobId);
        if (job.results.size() != job.totalTasks) throw new IOException("Cannot complete Job before every task result is committed");
        ObjectNode event = event("COMPLETE", jobId);
        event.set("summary", JsonUtils.getMapper().valueToTree(summary)); append(event);
        job.resultSummary = event.get("summary"); job.state = "COMPLETED"; job.error = null;
    }

    private StoredJob required(String id) throws IOException {
        StoredJob job = jobs.get(id); if (job == null) throw new IOException("Unknown persisted job " + id); return job;
    }

    private void append(ObjectNode event) throws IOException {
        if (closed) throw new IOException("Master state store is closed");
        byte[] json = (JsonUtils.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(json);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
    }

    private ObjectNode event(String type, String jobId) {
        ObjectNode event = JsonUtils.getMapper().createObjectNode();
        event.put("schemaVersion", 1); event.put("type", type); event.put("jobId", jobId); return event;
    }

    private String writeArtifact(String relative, byte[] bytes) throws IOException {
        Path target = artifactPath(relative); Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) output.write(buffer); output.force(true);
            }
            try { Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return relative.replace('\\', '/');
    }

    private Path artifactPath(String relative) throws IOException {
        Path path = artifacts.resolve(relative).normalize();
        if (!path.startsWith(artifacts)) throw new IOException("Invalid artifact path in Master journal");
        return path;
    }

    private static String safeSegment(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,160}")) throw new IOException("Invalid artifact identifier");
        return value;
    }

    private static String checksum(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
    }

    private byte[] readArtifact(String relative, String expectedChecksum) throws IOException {
        Path path = artifactPath(relative);
        byte[] bytes = Files.readAllBytes(path);
        if (!Objects.equals(checksum(bytes), expectedChecksum)) throw new IOException("Master artifact checksum mismatch: " + relative);
        return bytes;
    }

    private void replay() throws IOException {
        if (!Files.exists(journal)) return;
        long size = Files.size(journal);
        boolean hasTrailingNewline = size == 0;
        if (size > 0) try (FileChannel input = FileChannel.open(journal, StandardOpenOption.READ)) {
            ByteBuffer last = ByteBuffer.allocate(1); input.read(last, size - 1); last.flip(); hasTrailingNewline = last.get() == (byte) '\n';
        }
        Set<String> referencedArtifacts = new HashSet<>();
        long validOffset = 0;
        try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
            String line; long lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) { validOffset += line.getBytes(StandardCharsets.UTF_8).length + 1L; continue; }
                JsonNode e;
                try { e = JsonUtils.getMapper().readTree(line); }
                catch (Exception ex) {
                    if (!hasTrailingNewline && !reader.ready()) {
                        try (FileChannel repair = FileChannel.open(journal, StandardOpenOption.WRITE)) { repair.truncate(validOffset); repair.force(true); }
                        break; // Discard only a torn final append; all earlier corruption is fatal.
                    }
                    throw new IOException("Invalid Master journal at line " + lineNo, ex);
                }
                int schemaVersion = e.path("schemaVersion").asInt(0);
                if (schemaVersion < 0 || schemaVersion > 1) throw new IOException("Unsupported Master journal schema " + schemaVersion + " at line " + lineNo);
                String type = e.path("type").asText();
                if ("WORKER_ALIAS".equals(type)) {
                    String workerId = e.path("workerId").asText();
                    String displayName = e.path("displayName").asText();
                    if (workerId == null || workerId.isBlank()) {
                        throw new IOException("Missing workerId in Master journal line " + lineNo);
                    }
                    workerAliases.put(workerId, validateDisplayName(displayName));
                    validOffset += line.getBytes(StandardCharsets.UTF_8).length + ((reader.ready() || hasTrailingNewline) ? 1L : 0L);
                    continue;
                }
                String id = e.path("jobId").asText();
                if (id.isBlank()) throw new IOException("Missing jobId in Master journal line " + lineNo);
                switch (type) {
                    case "CREATE" -> {
                        if (jobs.containsKey(id)) throw new IOException("Duplicate CREATE for Job " + id + " at line " + lineNo);
                        JsonNode request = e.get("request");
                        long startedAt = e.path("startedAt").asLong();
                        if (e.hasNonNull("inputArtifact")) {
                            String artifact = e.path("inputArtifact").asText(); referencedArtifacts.add(artifact);
                            byte[] bytes = readArtifact(artifact, e.path("inputSha256").asText());
                            request = JsonUtils.getMapper().readTree(bytes);
                        }
                        StoredJob job = new StoredJob(id, request, startedAt,
                            e.path("deadlineAt").asLong(startedAt), e.path("totalTasks").asInt());
                        job.idempotencyKey = e.path("idempotencyKey").asText(null); job.requestHash = e.path("requestHash").asText(null);
                        jobs.put(id, job);
                        if (job.idempotencyKey != null) {
                            if (idempotencyKeys.containsKey(job.idempotencyKey)) throw new IOException("Duplicate idempotency key in journal at line " + lineNo);
                            idempotencyKeys.put(job.idempotencyKey, id); requestHashes.put(job.idempotencyKey, job.requestHash);
                        }
                    }
                    case "RESULT" -> {
                        StoredJob j = requireReplay(id, lineNo); TaskResult result;
                        if (e.hasNonNull("artifact")) result = JsonUtils.getMapper().readValue(
                            readArtifact(e.path("artifact").asText(), e.path("sha256").asText()), TaskResult.class);
                        else result = JsonUtils.getMapper().treeToValue(e.get("result"), TaskResult.class);
                        if (e.hasNonNull("artifact")) referencedArtifacts.add(e.path("artifact").asText());
                        j.results.put(result.getTaskId(), result); j.attempts.remove(result.getTaskId());
                    }
                    case "ATTEMPT" -> {
                        StoredJob j = requireReplay(id, lineNo); Map<String, Object> attempt = new LinkedHashMap<>();
                        attempt.put("attemptId", e.path("attemptId").asText()); attempt.put("workerId", e.path("workerId").asText());
                        attempt.put("workerSession", e.path("workerSession").asText()); attempt.put("masterEpoch", e.path("masterEpoch").asText());
                        attempt.put("assignedAt", e.path("assignedAt").asLong()); attempt.put("state", "ASSIGNED"); j.attempts.put(e.path("taskId").asText(), attempt);
                    }
                    case "ATTEMPT_STATE" -> {
                        StoredJob j = requireReplay(id, lineNo); Map<String, Object> attempt = j.attempts.get(e.path("taskId").asText());
                        if (attempt != null && Objects.equals(attempt.get("attemptId"), e.path("attemptId").asText())) {
                            attempt.put("state", e.path("state").asText()); attempt.put("changedAt", e.path("changedAt").asLong());
                            if (e.hasNonNull("reason")) attempt.put("reason", e.path("reason").asText());
                        }
                    }
                    case "STATE" -> { StoredJob j = requireReplay(id, lineNo); j.state = e.path("state").asText("RUNNING"); j.error = e.hasNonNull("error") ? e.path("error").asText() : null; }
                    case "COMPLETE" -> { StoredJob j = requireReplay(id, lineNo); j.resultSummary = e.get("summary"); j.state = "COMPLETED"; j.error = null; }
                    default -> throw new IOException("Unknown Master journal event " + type + " at line " + lineNo);
                }
                validOffset += line.getBytes(StandardCharsets.UTF_8).length + ((reader.ready() || hasTrailingNewline) ? 1L : 0L);
            }
        }
        cleanupOrphanArtifacts(referencedArtifacts);
    }

    private void ensureLineBoundary() throws IOException {
        long size = channel.size(); if (size == 0) return;
        ByteBuffer last = ByteBuffer.allocate(1);
        try (FileChannel input = FileChannel.open(journal, StandardOpenOption.READ)) {
            long position = size - 1;
            while (last.hasRemaining()) {
                int count = input.read(last, position);
                if (count < 0) throw new IOException("Master journal changed while checking its final line");
                position += count;
            }
        }
        last.flip();
        if (last.get() != (byte) '\n') {
            ByteBuffer newline = StandardCharsets.UTF_8.encode("\n");
            while (newline.hasRemaining()) channel.write(newline); channel.force(true);
        }
    }

    private void cleanupOrphanArtifacts(Set<String> referenced) throws IOException {
        if (!Files.exists(artifacts)) return;
        try (var files = Files.walk(artifacts)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String relative = artifacts.relativize(path).toString().replace('\\', '/');
                if (!referenced.contains(relative)) Files.deleteIfExists(path);
            }
        }
    }

    private void expirePreviousAttempts() throws IOException {
        long now = System.currentTimeMillis();
        for (StoredJob job : jobs.values()) for (Map.Entry<String, Map<String, Object>> entry : List.copyOf(job.attempts.entrySet())) {
            Object attemptId = entry.getValue().get("attemptId");
            if (attemptId != null) updateAttemptState(job.jobId, entry.getKey(), String.valueOf(attemptId),
                "EXPIRED", now, "Master epoch restarted; unfinished task will be scheduled again");
        }
    }

    private StoredJob requireReplay(String id, long lineNo) throws IOException {
        StoredJob job = jobs.get(id); if (job == null) throw new IOException("Journal event precedes CREATE at line " + lineNo); return job;
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true;
        try { channel.close(); }
        finally { try { lock.release(); } finally { lockChannel.close(); } }
    }
}
