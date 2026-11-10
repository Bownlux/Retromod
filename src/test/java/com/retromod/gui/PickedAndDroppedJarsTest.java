/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Picked and dropped jars take one path, and a jar Retromod cannot read sends the player to retromod-input/. */
class PickedAndDroppedJarsTest {

    @Test
    void jarsThatCannotBeOpenedAreNamed(@TempDir Path folder) throws IOException {
        File readable = Files.writeString(folder.resolve("readable.jar"), "PK").toFile();
        File missing = folder.resolve("missing.jar").toFile();
        File directory = Files.createDirectories(folder.resolve("folder.jar")).toFile();

        assertEquals(List.of("missing.jar", "folder.jar"),
                RetromodScreen.unreadableNames(List.of(readable, missing, directory)));
    }

    @Test
    void aChoiceWithAnUnreadableJarUpdatesNothingAndShowsTheFolder(@TempDir Path folder)
            throws IOException {
        // The client queues screen work for its main thread; the test only collects it.
        LinkedBlockingQueue<Runnable> mainThread = new LinkedBlockingQueue<>();
        RetromodScreen screen = new RetromodScreen((java.util.concurrent.Executor) mainThread::add);
        File readable = Files.writeString(folder.resolve("readable.jar"), "PK").toFile();
        File outside = folder.resolve("outside the sandbox.jar").toFile();

        assertFalse(screen.handleChosen(List.of(readable, outside)),
                "nothing is updated when one chosen jar cannot be read");
        assertEquals(1, mainThread.size(), "the retromod-input/ message is queued for the main thread");
    }

    @Test
    void droppedJarsTakeThePickedJarsPath(@TempDir Path folder) throws InterruptedException {
        LinkedBlockingQueue<Runnable> mainThread = new LinkedBlockingQueue<>();
        RetromodScreen screen = new RetromodScreen((java.util.concurrent.Executor) mainThread::add);

        // A jar the sandbox did not grant reaches the same retromod-input/ message as a picked one.
        screen.takeDropped(List.of(folder.resolve("notes.txt"), folder.resolve("old mod.jar")));

        assertNotNull(mainThread.poll(10, TimeUnit.SECONDS),
                "the dropped jar is checked off the main thread and answered on it");
    }

    @Test
    void aDropWithoutJarsDoesNothing(@TempDir Path folder) throws InterruptedException {
        LinkedBlockingQueue<Runnable> mainThread = new LinkedBlockingQueue<>();
        RetromodScreen screen = new RetromodScreen((java.util.concurrent.Executor) mainThread::add);

        screen.takeDropped(List.of(folder.resolve("screenshot.png"), folder.resolve("pack.zip")));

        assertNull(mainThread.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void thePickerStartsWhereItLastTookMods(@TempDir Path gameDir, @TempDir Path downloads)
            throws IOException {
        Path home = Path.of(System.getProperty("user.home"));
        assertEquals(home, LastPickedFolder.startFolder(gameDir), "the first picker starts at home");

        LastPickedFolder.remember(gameDir, downloads.toFile());

        assertTrue(Files.isRegularFile(gameDir.resolve("retromod-cache/file-picker.properties")),
                "the folder is kept in retromod-cache/, not Java preferences or config/");
        assertFalse(Files.exists(gameDir.resolve("config")),
                "config/ ships in modpack exports, so the personal path stays out of it");
        assertEquals(downloads.toAbsolutePath(), LastPickedFolder.startFolder(gameDir));

        Files.delete(downloads);
        assertEquals(home, LastPickedFolder.startFolder(gameDir),
                "a folder that is gone falls back to home");
    }

    @Test
    void anOversizedPickerFileIsIgnored(@TempDir Path gameDir) throws IOException {
        Path file = Files.createDirectories(gameDir.resolve("retromod-cache"))
                .resolve("file-picker.properties");
        Files.writeString(file, "last_folder=" + gameDir + "\n# " + "x".repeat(20_000));

        assertEquals(Path.of(System.getProperty("user.home")), LastPickedFolder.startFolder(gameDir));
    }
}
