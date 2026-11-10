/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Where transformers stage temporary work: the game folder in game, the system temp folder otherwise. */
public final class WorkFolder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-InPlace");

    /** Below the cache root; each launch works in its own folder, named after its process id. */
    static final String WORK = "work";
    /** The file each running launch keeps locked inside its folder. */
    static final String LOCK = ".lock";
    // A launch locks its folder right after making it, so a folder this new may not be locked yet.
    static final Duration STARTING_LAUNCH = Duration.ofMinutes(1);

    // Null until an entry point names the game folder, so the CLI keeps the system temp folder.
    private static volatile Path launchFolder;
    // Open for the whole launch, because closing the channel would release the lock.
    private static FileChannel launchLock;

    private WorkFolder() {}

    /** Stages this launch's work in retromod-cache/work/ and removes what ended launches left. */
    public static synchronized void useGameFolder(Path gameDir) {
        if (launchFolder != null) return;
        Path root = null;
        try {
            // Same rules as the translated-mod cache: no links below the game folder.
            Path realGameDir = gameDir.toRealPath();
            root = realGameDir.resolve(TranslatedModCache.CACHE_ROOT).resolve(WORK);
            TranslatedModCache.requireRealDirectoriesBelow(realGameDir, root);
            Files.createDirectories(root);
            TranslatedModCache.requireRealDirectoriesBelow(realGameDir, root);
            removeLeftovers(root);

            // A new folder every launch, since process ids repeat across containers.
            Path folder = Files.createTempDirectory(root, ProcessHandle.current().pid() + "-");
            try {
                launchLock = lockFolder(folder);
            } catch (IOException | RuntimeException e) {
                // An unlocked folder could be removed by another launch while this one works in it.
                TranslatedModCache.deleteTree(folder);
                throw e;
            }
            launchFolder = folder;
        } catch (IOException | RuntimeException e) {
            // The system temp folder still works, it is only off the game's file system.
            LOGGER.warn("Retromod stages its work in the system temp folder because it cannot use "
                    + "{}: {}", root != null ? root : gameDir, e.getMessage());
        }
    }

    /** A new folder for one piece of work, removed by the caller when it is done. */
    public static Path createTempDirectory(String prefix) throws IOException {
        Path folder = launchFolder;
        return folder == null ? Files.createTempDirectory(prefix)
                : Files.createTempDirectory(folder, prefix);
    }

    /** A new file for one piece of work, removed by the caller when it is done. */
    public static Path createTempFile(String prefix, String suffix) throws IOException {
        Path folder = launchFolder;
        return folder == null ? Files.createTempFile(prefix, suffix)
                : Files.createTempFile(folder, prefix, suffix);
    }

    /** This launch's folder, or null when work goes to the system temp folder. */
    static Path launchFolder() {
        return launchFolder;
    }

    /** Locks the folder's lock file; the lock lasts while the returned channel stays open. */
    static FileChannel lockFolder(Path folder) throws IOException {
        FileChannel channel = FileChannel.open(folder.resolve(LOCK), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            if (channel.tryLock() != null) return channel;
            throw new IOException(folder.resolve(LOCK) + " is locked by another process");
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /** Deletes the folders no running launch holds; a failure is logged and the rest go on. */
    static void removeLeftovers(Path root) {
        List<String> failures = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                try {
                    // A folder Retromod did not name, a link, or a starting launch's is left alone.
                    if (isLaunchFolder(child) && !isStarting(child) && launchEnded(child)) {
                        TranslatedModCache.deleteTree(child);
                    }
                } catch (IOException | RuntimeException e) {
                    failures.add(child.getFileName() + " (" + e.getMessage() + ")");
                }
            }
        } catch (IOException | RuntimeException e) {
            failures.add(root + " (" + e.getMessage() + ")");
        }
        if (!failures.isEmpty()) {
            LOGGER.warn("Retromod could not remove {} leftover work folder(s) and tries again on "
                    + "the next start: {}", failures.size(), String.join(", ", failures));
        }
    }

    /** True when no launch holds the folder's lock, so the launch that made it has ended. */
    static boolean launchEnded(Path folder) throws IOException {
        // The process id in the name proves nothing: another container or a hidden process can
        // reuse it, so only the lock decides.
        try (FileChannel channel = FileChannel.open(folder.resolve(LOCK),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            return channel.tryLock() != null;
        } catch (OverlappingFileLockException heldByThisProcess) {
            return false;
        }
    }

    /** True when the folder changed so recently that its launch may still be starting. */
    private static boolean isStarting(Path folder) throws IOException {
        Instant changed = Files.getLastModifiedTime(folder, LinkOption.NOFOLLOW_LINKS).toInstant();
        return changed.isAfter(Instant.now().minus(STARTING_LAUNCH));
    }

    /** A real folder whose name starts with a process id and a dash, as this class names them. */
    private static boolean isLaunchFolder(Path child) {
        String name = child.getFileName().toString();
        int dash = name.indexOf('-');
        if (dash <= 0) return false;
        for (int i = 0; i < dash; i++) {
            if (name.charAt(i) < '0' || name.charAt(i) > '9') return false;
        }
        return Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
    }

    /** Goes back to the system temp folder and releases the lock, for tests. */
    static synchronized void useSystemTempForTesting() {
        launchFolder = null;
        if (launchLock != null) {
            try {
                launchLock.close();
            } catch (IOException ignored) {
                // A test only needs the lock gone.
            }
            launchLock = null;
        }
    }
}
