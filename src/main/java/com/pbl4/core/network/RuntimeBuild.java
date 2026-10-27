package com.pbl4.core.network;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Fingerprint of the JAR actually loaded by this JVM, captured once at startup. */
public final class RuntimeBuild {
    private RuntimeBuild() { }
    public static final String SHA256 = fingerprint();
    private static String fingerprint() {
        try {
            Path location = Path.of(RuntimeBuild.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(location)) return null; // Development classes, not a packaged JAR.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(location)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) { return null; }
    }
}
