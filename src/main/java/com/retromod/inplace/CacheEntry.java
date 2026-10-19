/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import java.util.List;
import java.util.Objects;

/**
 * One source jar's record in the cache index, keyed by the source's SHA-256.
 *
 * <p>Only {@link Status#OK} entries name a translated file. The other states are negative or
 * in-progress records that stop the same jar from being retried on every launch.
 *
 * @param sourceSha256    SHA-256 of the original jar, lowercase hex
 * @param status          what happened to this source
 * @param file            translated file name inside the cache's {@code jars/} folder, or null
 * @param fileSha256      SHA-256 of the translated file, or null
 * @param fileFingerprint size, modification time, and file key seen when the file was
 *                        published; a record only, since serving always rehashes the file
 * @param modIds          mod ids the translated jar declares, possibly empty
 * @param modVersion      the first mod's version, or null
 * @param sourceName      file name of the original, for logs and export
 * @param sourceLocation  folder the original came from, such as {@code mods}
 * @param sourceSize      size of the original in bytes
 * @param failure         one-line reason for a failed, retry, or skipped entry, or null
 * @param attempts        launches this source stayed pending
 * @param retromodVersion Retromod version that wrote the entry
 * @param updated         epoch milliseconds of the last change
 */
public record CacheEntry(
        String sourceSha256,
        Status status,
        String file,
        String fileSha256,
        String fileFingerprint,
        List<String> modIds,
        String modVersion,
        String sourceName,
        String sourceLocation,
        long sourceSize,
        String failure,
        int attempts,
        String retromodVersion,
        long updated) {

    /** Lifecycle of one source jar in the cache. */
    public enum Status {
        /** Translated and published. The only state that may be served. */
        OK,
        /**
         * Translation failed for a reason that will repeat. Not retried until the Retromod
         * build, the transform context, or the source file changes.
         */
        FAILED,
        /**
         * Translation did not finish for a transient reason, such as an I/O error, a busy
         * lock, or an interrupted launch. Retried on the next launch.
         */
        RETRY,
        /**
         * The original was hidden and is waiting for translation later in the launch.
         * {@link CacheEntry#attempts()} counts how many launches it stayed that way, so a
         * caller can stop hiding a jar whose translation never runs.
         */
        PENDING,
        /**
         * Retromod's entry point began translating this source and has not recorded a result.
         * Written to disk before the transformer runs, so a launch that dies during
         * translation (out of memory, a killed process) still leaves a record. The discovery
         * pass counts these, so such a source ends as {@link #FAILED} instead of being
         * translated, and crashing, on every launch.
         */
        STARTED,
        /** The player chose not to translate this file. */
        SKIPPED
    }

    public CacheEntry {
        Objects.requireNonNull(sourceSha256, "sourceSha256");
        Objects.requireNonNull(status, "status");
        modIds = modIds == null ? List.of() : List.copyOf(modIds);
        if (status == Status.OK && (file == null || fileSha256 == null)) {
            throw new IllegalArgumentException("An OK cache entry needs a file and its hash");
        }
    }

    /** True when the cache may try to translate this source again on this launch. */
    public boolean allowsAttempt() {
        return status == Status.RETRY || status == Status.PENDING || status == Status.STARTED;
    }
}
