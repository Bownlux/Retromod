/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import java.nio.file.Path;
import java.util.Objects;

/**
 * An original jar the cache was asked about, with its content hash.
 *
 * @param path        the path exactly as the caller found it. Loader APIs such as
 *                    {@code ILaunchContext.isLocated} compare raw paths, so it is never
 *                    normalized or resolved here.
 * @param location    the input folder, such as {@code mods} or {@code retromod-input}
 * @param fileName    the original file name
 * @param size        size in bytes
 * @param fingerprint size, modification time, change time, and file key when hashed
 * @param sha256      SHA-256 of the file's bytes, lowercase hex; the cache key
 */
public record SourceJar(Path path, String location, String fileName, long size,
        String fingerprint, String sha256) {

    public SourceJar {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(sha256, "sha256");
    }
}
