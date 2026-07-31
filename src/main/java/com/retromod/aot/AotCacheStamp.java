/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 *
 * AOT cache generation stamp: auto-clears the cache when the Retromod build changes.
 */
package com.retromod.aot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Keeps ahead-of-time cache entries tied to the Retromod build that created
 * them. Packaged builds use both the version and self-hash. An unpackaged
 * development run can only use the version, so same-version experiments may
 * still require a manual cache clear.
 */
public final class AotCacheStamp {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-aot");

    private static final String STAMP_FILE = ".cache-stamp";

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
    public static void ensureCurrent(Path cacheDir) {
        ensureCurrent(cacheDir, expectedStamp());
    }

    /** Testable variant with an explicit stamp value. */
    static void ensureCurrent(Path cacheDir, String stamp) {
        try {
            Path stampFile = cacheDir.resolve(STAMP_FILE);
            if (Files.isDirectory(cacheDir)) {
                String recorded = Files.exists(stampFile)
                    ? Files.readString(stampFile, StandardCharsets.UTF_8).trim()
                    : null;
                if (stamp.equals(recorded)) {
                    return;
                }
                boolean empty;
                try (var s = Files.list(cacheDir)) {
                    empty = s.findFirst().isEmpty();
                }
                if (!empty) {
                    LOGGER.info("Retromod build changed since the AOT cache was written "
                            + "(recorded: {}), clearing {}",
                            recorded == null ? "no stamp" : recorded, cacheDir);
                    wipe(cacheDir);
                    // A failed delete must leave the old stamp in place. Otherwise the
                    // next launch could trust a stale file as current.
                    if (Files.isDirectory(cacheDir)) {
                        try (var s = Files.list(cacheDir)) {
                            if (s.findFirst().isPresent()) {
                                LOGGER.warn("Could not fully clear the AOT cache at {}. "
                                        + "It will be checked again next launch.", cacheDir);
                                return;
                            }
                        }
                    }
                }
            }
            Files.createDirectories(cacheDir);
            Files.writeString(stampFile, stamp + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not validate the AOT cache at {}: {}",
                    cacheDir, e.toString());
        }
    }

    /** Delete everything under {@code cacheDir}, including the directory itself. */
    private static void wipe(Path cacheDir) throws IOException {
        try (var paths = Files.walk(cacheDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    LOGGER.warn("Could not delete stale AOT cache entry: {}", p);
                }
            });
        }
    }
}
