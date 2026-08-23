/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.jar.JarFile;

/** Stages exact archive copies before replacing a published file. */
public final class ArchivePublication {

    private ArchivePublication() {}

    @FunctionalInterface
    interface StagedCopy {
        void copy(Path source, Path staged) throws IOException;
    }

    /** Publishes an exact copy only after a complete sibling stage is validated. */
    public static void copyReplacing(Path source, Path target) throws IOException {
        copyReplacing(source, target,
                (validatedSource, staged) -> Files.copy(
                        validatedSource, staged, StandardCopyOption.REPLACE_EXISTING));
    }

    /** Publishes an exact copy without overwriting a file created by another operation. */
    public static void copyNew(Path source, Path target) throws IOException {
        copy(source, target,
                (validatedSource, staged) -> Files.copy(
                        validatedSource, staged, StandardCopyOption.REPLACE_EXISTING), false);
    }

    /** Publishes an exact copy, then removes the source archive. */
    public static void moveReplacing(Path source, Path target) throws IOException {
        Path validatedSource = ZipSecurity.requireRegularFileNoFollow(
                source, "archive move source");
        Path absoluteTarget = requireDistinctTarget(validatedSource, target);
        copyReplacing(validatedSource, absoluteTarget);
        Files.delete(validatedSource);
    }

    static void copyReplacing(Path source, Path target, StagedCopy copyOperation)
            throws IOException {
        copy(source, target, copyOperation, true);
    }

    private static void copy(Path source, Path target, StagedCopy copyOperation,
            boolean replaceExisting) throws IOException {
        Objects.requireNonNull(copyOperation, "copyOperation");
        Path validatedSource = ZipSecurity.requireRegularFileNoFollow(
                source, "archive copy source");
        Path absoluteTarget = requireDistinctTarget(validatedSource, target);
        Path parent = absoluteTarget.getParent();
        if (parent == null
                || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Archive destination directory is not a regular "
                    + "non-symlink directory: " + parent);
        }
        if (Files.isSymbolicLink(absoluteTarget)
                || (Files.exists(absoluteTarget, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(absoluteTarget, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Archive destination is not a regular file: "
                    + absoluteTarget);
        }
        if (!replaceExisting && Files.exists(absoluteTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Archive destination already exists: " + absoluteTarget);
        }

        Path staged = Files.createTempFile(parent,
                "." + absoluteTarget.getFileName() + ".", ".tmp");
        try {
            copyOperation.copy(validatedSource, staged);
            requireCompleteExactCopy(validatedSource, staged);
            publishStaged(staged, absoluteTarget, replaceExisting);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static Path requireDistinctTarget(Path source, Path target) throws IOException {
        if (target == null) throw new IOException("Archive destination is missing");
        Path absoluteTarget = target.toAbsolutePath().normalize();
        boolean same = source.equals(absoluteTarget);
        if (!same && Files.exists(absoluteTarget, LinkOption.NOFOLLOW_LINKS)) {
            same = Files.isSameFile(source, absoluteTarget);
        }
        if (same) {
            throw new IOException("Archive source and destination must differ: " + source);
        }
        return absoluteTarget;
    }

    private static void requireCompleteExactCopy(Path source, Path staged) throws IOException {
        if (Files.isSymbolicLink(staged)
                || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Staged archive copy is not a regular file: " + staged);
        }
        if (Files.size(source) != Files.size(staged)
                || Files.mismatch(source, staged) != -1) {
            throw new IOException("Staged archive copy does not match the source: "
                    + source.getFileName());
        }
        try (JarFile ignored = new JarFile(staged.toFile(), false)) {
            // Opening the exact staged copy validates its central directory.
        }
    }

    private static void publishStaged(Path staged, Path target, boolean replaceExisting)
            throws IOException {
        if (!replaceExisting) {
            // ATOMIC_MOVE leaves existing-target behavior provider-specific. A no-option move
            // keeps copyNew fail-closed when another process wins the destination race.
            Files.move(staged, target);
            return;
        }
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
