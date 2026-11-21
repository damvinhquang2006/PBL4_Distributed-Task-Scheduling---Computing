package com.pbl4.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** OS-backed ownership lease used to prevent duplicate launchers and orphaned child JVMs. */
public final class SupervisorLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final long pid;
    private final String token;

    private SupervisorLease(FileChannel channel, FileLock lock, long pid, String token) {
        this.channel = channel; this.lock = lock; this.pid = pid; this.token = token;
    }

    public static SupervisorLease acquire(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        FileChannel channel = FileChannel.open(normalized, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException e) { lock = null; }
            if (lock == null) throw new IOException("Another supervisor owns " + normalized);
            long pid = ProcessHandle.current().pid(); String token = UUID.randomUUID().toString();
            byte[] owner = ("pid=" + pid + "\ntoken=" + token + "\nstartedAt=" + System.currentTimeMillis() + "\n")
                .getBytes(StandardCharsets.UTF_8);
            Path ownerPath = ownerPath(normalized);
            try (FileChannel metadata = FileChannel.open(ownerPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer data = ByteBuffer.wrap(owner); while (data.hasRemaining()) metadata.write(data); metadata.force(true);
            }
            return new SupervisorLease(channel, lock, pid, token);
        } catch (IOException | RuntimeException e) { channel.close(); throw e; }
    }

    public static boolean isOwnedBy(Path path, long expectedPid, String expectedToken) {
        Path normalized = path.toAbsolutePath().normalize();
        try (FileChannel channel = FileChannel.open(path.toAbsolutePath().normalize(), StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException e) { lock = null; }
            if (lock != null) { lock.release(); return false; }
        } catch (IOException e) { return false; }
        try {
            String owner = Files.readString(ownerPath(normalized), StandardCharsets.UTF_8);
            return owner.lines().anyMatch(line -> line.equals("pid=" + expectedPid))
                && owner.lines().anyMatch(line -> line.equals("token=" + expectedToken));
        } catch (IOException e) { return false; }
    }

    private static Path ownerPath(Path lockPath) {
        return lockPath.resolveSibling(lockPath.getFileName() + ".owner");
    }

    public boolean isValid() { return lock.isValid(); }
    public long pid() { return pid; }
    public String token() { return token; }

    @Override public void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
