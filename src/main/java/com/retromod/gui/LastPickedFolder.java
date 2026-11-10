/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.inplace.TranslatedModCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Remembers the folder the file picker last took mods from, in retromod-cache/. */
final class LastPickedFolder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Screen");
    // Not in config/: the path is personal, and modpack exports ship config/.
    static final Path FILE = Path.of(TranslatedModCache.CACHE_ROOT, "file-picker.properties");
    private static final String KEY = "last_folder";
    // The file holds one path, so anything this large was not written by Retromod.
    private static final long MAX_BYTES = 16 * 1024;

    private LastPickedFolder() {}

    /** The remembered folder while it still exists, otherwise the home folder. */
    static Path startFolder(Path gameDir) {
        Path home = Path.of(System.getProperty("user.home", "."));
        Path file = gameDir.resolve(FILE);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_BYTES) {
                return home;
            }
            Properties saved = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                saved.load(reader);
            }
            String folder = saved.getProperty(KEY, "");
            Path remembered = folder.isBlank() ? null : Path.of(folder);
            return remembered != null && Files.isDirectory(remembered) ? remembered : home;
        } catch (IOException | RuntimeException unreadable) {
            return home;
        }
    }

    /** Saves the folder for the next picker; a failure only costs that convenience. */
    static void remember(Path gameDir, File folder) {
        if (folder == null) return;
        Path file = gameDir.resolve(FILE);
        try {
            Files.createDirectories(file.getParent());
            Properties saved = new Properties();
            saved.setProperty(KEY, folder.getAbsolutePath());
            // Write a temporary file first, so a failed write never leaves half a file.
            Path staged = Files.createTempFile(file.getParent(), ".file-picker.", ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(staged, StandardCharsets.UTF_8)) {
                    saved.store(writer, "Retromod file picker");
                }
                moveReplacing(staged, file);
            } finally {
                Files.deleteIfExists(staged);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Could not remember the file picker folder in {}: {}", file, e.getMessage());
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
