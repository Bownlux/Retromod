/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** In game, work is staged under the game folder; the CLI keeps the system temp folder. */
class WorkFolderTest {

    private static final long CURRENT_PID = ProcessHandle.current().pid();

    @BeforeEach
    @AfterEach
    void backToTheSystemTempFolder() {
        WorkFolder.useSystemTempForTesting();
    }

    @Test
    void withoutAGameFolderWorkGoesToTheSystemTempFolder() throws IOException {
        // The CLI never names a game folder.
        Path systemTemp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path folder = WorkFolder.createTempDirectory("retromod-test-");
        Path file = WorkFolder.createTempFile("retromod-test-", ".jar");
        try {
            assertNull(WorkFolder.launchFolder());
            assertEquals(systemTemp, folder.toRealPath().getParent());
            assertEquals(systemTemp, file.toRealPath().getParent());
        } finally {
            Files.deleteIfExists(folder);
            Files.deleteIfExists(file);
        }
    }

    @Test
    void inGameWorkGoesUnderTheGameFolder(@TempDir Path gameDir) throws IOException {
        WorkFolder.useGameFolder(gameDir);

        Path launch = WorkFolder.launchFolder();
        assertNotNull(launch);
        assertEquals(gameDir.toRealPath().resolve("retromod-cache").resolve("work"),
                launch.getParent());
        assertTrue(launch.getFileName().toString().startsWith(CURRENT_PID + "-"),
                "the folder is named after the launch's process id: " + launch);
        assertEquals(launch, WorkFolder.createTempDirectory("retromod-forge-").getParent());
        assertEquals(launch, WorkFolder.createTempFile("retromod-source-", ".jar").getParent());
    }

    @Test
    void theRunningLaunchHoldsItsLock(@TempDir Path gameDir) throws IOException {
        WorkFolder.useGameFolder(gameDir);

        assertFalse(WorkFolder.launchEnded(WorkFolder.launchFolder()),
                "another launch must see this one as running");
    }

    @Test
    void theFirstEntryPointDecidesForTheLaunch(@TempDir Path first, @TempDir Path second)
            throws IOException {
        WorkFolder.useGameFolder(first);
        WorkFolder.useGameFolder(second);

        assertTrue(WorkFolder.launchFolder().startsWith(first.toRealPath()));
        assertFalse(Files.exists(second.resolve("retromod-cache")));
    }

    @Test
    void startupRemovesOnlyFoldersNoLaunchHolds(@TempDir Path gameDir) throws IOException {
        Path work = Files.createDirectories(gameDir.resolve("retromod-cache").resolve("work"));
        Path crashed = leftover(work, deadPid() + "-1", true);
        Path neverLocked = leftover(work, deadPid() + "-2", false);
        Path foreign = Files.createDirectories(work.resolve("notes"));

        // The lock decides, not the process id: a dead id can belong to a running launch in
        // another container, and a live id to a launch that crashed.
        Path heldDeadId = leftover(work, deadPid() + "-3", true);
        long liveId = ProcessHandle.current().parent().orElseThrow().pid();
        Path freeLiveId = leftover(work, liveId + "-4", true);
        try (FileChannel running = WorkFolder.lockFolder(heldDeadId)) {
            WorkFolder.useGameFolder(gameDir);
        }

        assertFalse(Files.exists(crashed), "a crashed launch's work is removed");
        assertFalse(Files.exists(neverLocked), "a folder that never got its lock is removed");
        assertFalse(Files.exists(freeLiveId), "a free lock means the launch ended");
        assertTrue(Files.isDirectory(heldDeadId), "a held lock means the launch still runs");
        assertTrue(Files.isDirectory(foreign), "a folder Retromod did not name stays");
    }

    @Test
    void aFolderThatMayStillBeStartingIsKept(@TempDir Path gameDir) throws IOException {
        Path work = Files.createDirectories(gameDir.resolve("retromod-cache").resolve("work"));
        // Made just now and not locked yet, like a launch between its first two steps.
        Path starting = Files.createDirectories(work.resolve(deadPid() + "-5"));

        WorkFolder.useGameFolder(gameDir);

        assertTrue(Files.isDirectory(starting));
    }

    @Test
    void aFolderThatCannotBeRemovedDoesNotStopTheRest(@TempDir Path gameDir) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path work = Files.createDirectories(gameDir.resolve("retromod-cache").resolve("work"));
        Path stuck = leftover(work, deadPid() + "-6", true);
        Path stuckPart = Files.createDirectories(stuck.resolve("part"));
        Files.writeString(stuckPart.resolve("half.jar"), "left by a crash");
        Files.setPosixFilePermissions(stuckPart, PosixFilePermissions.fromString("r-xr-xr-x"));
        Files.setLastModifiedTime(stuck, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
        Path removable = leftover(work, deadPid() + "-7", true);
        try {
            assumeFalse(Files.isWritable(stuckPart), "an administrator can delete anything");

            String log = captureErr(() -> WorkFolder.useGameFolder(gameDir));

            assertFalse(Files.exists(removable), "the other leftovers are still removed");
            assertTrue(Files.isDirectory(stuck));
            assertNotNull(WorkFolder.launchFolder(), "the launch still works in the game folder");
            assertEquals(1, occurrences(log, "leftover work folder"), "one warning: " + log);
        } finally {
            Files.setPosixFilePermissions(stuckPart, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aLinkedWorkFolderIsRefused(@TempDir Path gameDir, @TempDir Path elsewhere)
            throws IOException {
        Path cache = Files.createDirectories(gameDir.resolve("retromod-cache"));
        Files.createSymbolicLink(cache.resolve("work"), elsewhere);

        WorkFolder.useGameFolder(gameDir);

        assertNull(WorkFolder.launchFolder(), "work stays off a link, in the system temp folder");
        try (var listing = Files.list(elsewhere)) {
            assertEquals(0, listing.count(), "nothing is written through the link");
        }
    }

    /** A launch folder from an earlier start, with its lock file when it got that far. */
    private static Path leftover(Path work, String name, boolean withLockFile) throws IOException {
        Path folder = Files.createDirectories(work.resolve(name));
        Path part = Files.createDirectories(folder.resolve("retromod-forge-1"));
        Files.writeString(part.resolve("half.jar"), "left by a crash");
        if (withLockFile) Files.createFile(folder.resolve(WorkFolder.LOCK));
        // Older than a starting launch, which cleanup leaves alone.
        Files.setLastModifiedTime(folder, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
        return folder;
    }

    /** A process id that no running process has. */
    private static long deadPid() {
        for (long pid = Integer.MAX_VALUE; ; pid--) {
            if (ProcessHandle.of(pid).isEmpty()) return pid;
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) count++;
        return count;
    }

    /** slf4j-simple looks up System.err on every write, so swapping it captures the log. */
    private static String captureErr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
