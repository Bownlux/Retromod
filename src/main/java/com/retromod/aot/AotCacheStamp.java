/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 *
 * AOT cache generation stamp: auto-clears the cache when the Retromod build changes.
 */
package com.retromod.aot;

import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Keeps ahead-of-time cache entries tied to the Retromod build that created
 * them. Packaged builds use both the version and self-hash. An unpackaged
 * development run can only use the version, so same-version experiments may
 * still require a manual cache clear.
 */
public final class AotCacheStamp {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-aot");

    private static final String STAMP_FILE = ".cache-stamp";
    private static final int MAX_EXPECTED_STAMP_BYTES = 512;
    private static final int MAX_STAMP_FILE_BYTES = 1024;

    /** Self-hash of the running Retromod jar; empty when unresolvable (dev classpath). */
    private static volatile String selfHashCache;

    private AotCacheStamp() {}

    static String currentSelfHash() {
        String s = selfHashCache;
        if (s != null) return s;
        try {
            com.retromod.security.SignatureVerifier.VerificationResult r =
                com.retromod.security.SignatureVerifier.verify();
            String h = r.selfHash();
            selfHashCache = (h != null ? h : "");
        } catch (Throwable t) {
            selfHashCache = "";
        }
        return selfHashCache;
    }

    private static String expectedStamp() {
        return AotCompiler.AOT_VERSION + "|" + currentSelfHash();
    }

    /** Creates the cache or clears it when it belongs to another Retromod build. */
    public static boolean ensureCurrent(Path cacheDir) {
        return ensureCurrent(cacheDir, expectedStamp());
    }

    /** Clears the cache and writes the current build stamp before it can be reused. */
    static boolean clearCurrent(Path cacheDir) {
        return clearAndStamp(cacheDir, expectedStamp());
    }

    /** Creates a cache stamp that also binds cached classes to one exact Minecraft target. */
    static boolean ensureCurrentForTarget(Path cacheDir, String targetVersion) {
        if (targetVersion == null || targetVersion.isBlank()
                || targetVersion.indexOf('\n') >= 0 || targetVersion.indexOf('\r') >= 0) {
            LOGGER.warn("Could not validate the AOT cache because its target version is invalid");
            return false;
        }
        return ensureCurrent(cacheDir, expectedStamp() + "|target=" + targetVersion);
    }

    /** Clears a target-specific cache and restores its complete stamp. */
    static boolean clearCurrentForTarget(Path cacheDir, String targetVersion) {
        if (targetVersion == null || targetVersion.isBlank()
                || targetVersion.indexOf('\n') >= 0 || targetVersion.indexOf('\r') >= 0) {
            return false;
        }
        return clearAndStamp(cacheDir, expectedStamp() + "|target=" + targetVersion);
    }

    private static boolean clearAndStamp(Path cacheDir, String stamp) {
        if (!isValidExpectedStamp(stamp)) return false;
        try {
            Path validated = validateCachePath(cacheDir);
            if (Files.exists(validated, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(validated, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("AOT cache path is not a regular directory: "
                            + validated);
                }
                wipe(validated);
            }
            return ensureCurrent(validated, stamp);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not clear and restamp the AOT cache at {}: {}",
                    cacheDir, e.toString());
            return false;
        }
    }

    /** Testable variant with an explicit stamp value. */
    static boolean ensureCurrent(Path cacheDir, String stamp) {
        if (!isValidExpectedStamp(stamp)) {
            LOGGER.warn("Could not validate the AOT cache because its expected stamp is invalid");
            return false;
        }
        try {
            cacheDir = validateCachePath(cacheDir);
            if (Files.exists(cacheDir, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(cacheDir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("AOT cache path is not a regular directory: " + cacheDir);
            }
            Path stampFile = cacheDir.resolve(STAMP_FILE);
            if (Files.isDirectory(cacheDir, LinkOption.NOFOLLOW_LINKS)) {
                validateCachePath(cacheDir);
                String recorded;
                try {
                    recorded = readRecordedStamp(stampFile);
                } catch (IOException e) {
                    LOGGER.info("The AOT cache stamp is unreadable, clearing {}", cacheDir);
                    recorded = null;
                }
                if (stamp.equals(recorded)) {
                    return true;
                }
                boolean empty;
                validateCachePath(cacheDir);
                try (var s = Files.list(cacheDir)) {
                    empty = s.findFirst().isEmpty();
                }
                if (!empty) {
                    LOGGER.info("Retromod build changed since the AOT cache was written, clearing {}",
                            cacheDir);
                    wipe(cacheDir);
                    // A failed delete must leave the old stamp in place. Otherwise the
                    // next launch could trust a stale file as current.
                    if (Files.isDirectory(cacheDir, LinkOption.NOFOLLOW_LINKS)) {
                        validateCachePath(cacheDir);
                        try (var s = Files.list(cacheDir)) {
                            if (s.findFirst().isPresent()) {
                                LOGGER.warn("Could not fully clear the AOT cache at {}. "
                                        + "It will be checked again next launch.", cacheDir);
                                return false;
                            }
                        }
                    }
                }
            }
            validateCachePath(cacheDir);
            Files.createDirectories(cacheDir);
            validateCachePath(cacheDir);
            if (!Files.isDirectory(cacheDir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("AOT cache path is not a regular directory: " + cacheDir);
            }
            writeStampAtomically(cacheDir, stampFile, stamp);
            validateCachePath(cacheDir);
            return stamp.equals(readRecordedStamp(stampFile));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not validate the AOT cache at {}: {}",
                    cacheDir, e.toString());
            return false;
        }
    }

    private static boolean isValidExpectedStamp(String stamp) {
        if (stamp == null || stamp.isBlank() || stamp.indexOf('\n') >= 0
                || stamp.indexOf('\r') >= 0) {
            return false;
        }
        return stamp.getBytes(StandardCharsets.UTF_8).length <= MAX_EXPECTED_STAMP_BYTES;
    }

    private static Path validateCachePath(Path cacheDir) throws IOException {
        if (cacheDir == null) {
            throw new IOException("AOT cache path is missing");
        }
        Path absolute = cacheDir.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) {
            throw new IOException("AOT cache path is not absolute: " + cacheDir);
        }
        for (Path component : absolute) {
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
            if (Files.isSymbolicLink(current)) {
                throw new IOException("AOT cache path crosses a symbolic link: " + current);
            }
        }
        return absolute;
    }

    private static String readRecordedStamp(Path stampFile) throws IOException {
        if (!Files.exists(stampFile, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(stampFile, LinkOption.NOFOLLOW_LINKS)) return null;
        try (InputStream input = Files.newInputStream(stampFile)) {
            return new String(ZipSecurity.safeReadAllBytes(input, MAX_STAMP_FILE_BYTES),
                    StandardCharsets.UTF_8).trim();
        }
    }

    private static void writeStampAtomically(Path cacheDir, Path stampFile, String stamp)
            throws IOException {
        validateCachePath(cacheDir);
        Path staged = Files.createTempFile(cacheDir, ".cache-stamp-", ".tmp");
        try {
            validateCachePath(cacheDir);
            Files.writeString(staged, stamp + "\n", StandardCharsets.UTF_8);
            if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("AOT cache stamp staging path is not a regular file");
            }
            validateCachePath(cacheDir);
            try {
                Files.move(staged, stampFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged, stampFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
        if (!Files.isRegularFile(stampFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("AOT cache stamp is not a regular file");
        }
    }

    /** Delete everything under {@code cacheDir}, including the directory itself. */
    private static void wipe(Path cacheDir) throws IOException {
        validateCachePath(cacheDir);
        Files.walkFileTree(cacheDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
