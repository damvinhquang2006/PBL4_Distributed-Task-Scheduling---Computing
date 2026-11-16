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
final class SupervisorLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final long pid;
    private final String token;

    private SupervisorLease(FileChannel channel, FileLock lock, long pid, String token) {
        this.channel = channel; this.lock = lock; this.pid = pid; this.token = token;
    }

    static SupervisorLease acquire(Path path) throws IOException {
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
            channel.truncate(0); channel.position(0);
            ByteBuffer data = ByteBuffer.wrap(owner); while (data.hasRemaining()) channel.write(data); channel.force(true);
            return new SupervisorLease(channel, lock, pid, token);
        } catch (IOException | RuntimeException e) { channel.close(); throw e; }
    }

    static boolean isOwnedBy(Path path, long expectedPid, String expectedToken) {
        try (FileChannel channel = FileChannel.open(path.toAbsolutePath().normalize(), StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException e) { lock = null; }
            if (lock != null) { lock.release(); return false; }
            ByteBuffer content = ByteBuffer.allocate((int) Math.min(channel.size(), 4096)); channel.position(0);
            while (content.hasRemaining() && channel.read(content) > 0) { }
            String owner = new String(content.array(), 0, content.position(), StandardCharsets.UTF_8);
            return owner.lines().anyMatch(line -> line.equals("pid=" + expectedPid))
                && owner.lines().anyMatch(line -> line.equals("token=" + expectedToken));
        } catch (IOException e) { return false; }
    }

    boolean isValid() { return lock.isValid(); }
    long pid() { return pid; }
    String token() { return token; }

    @Override public void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
