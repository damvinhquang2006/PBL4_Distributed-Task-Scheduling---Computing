package com.pbl4.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SupervisorLeaseTest {
    @TempDir Path tempDir;

    @Test void readsOwnerMetadataBesideHeldLockAndRejectsReleasedLease() throws Exception {
        Path lockPath = tempDir.resolve("worker.lock");
        SupervisorLease lease = SupervisorLease.acquire(lockPath);
        try {
            assertTrue(Files.exists(tempDir.resolve("worker.lock.owner")));
            assertTrue(SupervisorLease.isOwnedBy(lockPath, lease.pid(), lease.token()));
            assertFalse(SupervisorLease.isOwnedBy(lockPath, lease.pid() + 1, lease.token()));
        } finally {
            lease.close();
        }
        assertFalse(SupervisorLease.isOwnedBy(lockPath, lease.pid(), lease.token()));
    }
}
