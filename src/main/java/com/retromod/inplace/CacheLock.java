/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;

/**
 * Exclusive lock on {@code retromod-cache/.lock}, held for a whole cache pass.
 *
 * <p>The lock file sits at the cache root on purpose. A stamp mismatch wipes the translated
 * folder, and if the lock lived inside it, a second process could unlink the held file on
 * POSIX, create a new one, and lock that too. The lock file is never deleted for the same
 * reason.
 *
 * <p>The JVM tracks file locks per process, so a second attempt from the same JVM (the
 * discovery-time engine and the in-game entry point run in one process) reports
 * {@link OverlappingFileLockException}. That is treated as "busy", the same as another game.
 */
final class CacheLock implements AutoCloseable {

    private static final long POLL_MILLIS = 50;

    private final FileChannel channel;
    private final FileLock lock;

    private CacheLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Waits up to {@code wait} for the lock. Empty when another holder keeps it the whole time.
     *
     * @throws IOException when the lock file cannot be opened or is not a regular file
     */
    static Optional<CacheLock> acquire(Path lockFile, Duration wait) throws IOException {
        if (Files.isSymbolicLink(lockFile)
                || (Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Cache lock is not a regular file: " + lockFile);
        }
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        long deadline = System.nanoTime() + Math.max(0, wait.toNanos());
        try {
            while (true) {
                FileLock lock = tryLock(channel);
                if (lock != null) {
                    return Optional.of(new CacheLock(channel, lock));
                }
                if (System.nanoTime() >= deadline) {
                    channel.close();
                    return Optional.empty();
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    channel.close();
                    return Optional.empty();
                }
            }
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException heldByThisJvm) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (lock.isValid()) lock.release();
        } finally {
            channel.close();
        }
    }
}
