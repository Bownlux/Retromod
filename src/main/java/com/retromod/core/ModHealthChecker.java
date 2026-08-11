/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.jar.*;

/**
 * Watches transformed mods for repeated errors and keeps their backups easy to restore.
 */
public final class ModHealthChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Health");

    private static final String BACKUP_FOLDER = "retromod-backups";

    private static final Map<String, ModHealthInfo> monitoredMods = new ConcurrentHashMap<>();

    private static final Map<String, List<String>> modErrors = new ConcurrentHashMap<>();

    private static final int ERROR_THRESHOLD = 3;

    private ModHealthChecker() {
    }
    
    public record ModHealthInfo(
        String modId,
        String modName,
        Path transformedPath,
        Path backupPath,
        long transformTime,
        boolean isHealthy
    ) {}
    
    /** Create the input/processed/backup folders. Call early during mod init. */
    public static void ensureFoldersExist(Path gameDir) {
        try {
            Path inputFolder = gameDir.resolve("retromod-input");
            if (!Files.exists(inputFolder)) {
                Files.createDirectories(inputFolder);
                LOGGER.info("Created retromod-input/ folder");
                createReadme(inputFolder);
            }

            Path processedFolder = inputFolder.resolve("processed");
            if (!Files.exists(processedFolder)) {
                Files.createDirectories(processedFolder);
            }

            Path backupFolder = gameDir.resolve(BACKUP_FOLDER);
            if (!Files.exists(backupFolder)) {
                Files.createDirectories(backupFolder);
                LOGGER.info("Created retromod-backups/ folder");
            }

        } catch (IOException e) {
            LOGGER.error("Could not create Retromod folders", e);
        }
    }

    private static void createReadme(Path inputFolder) {
        try {
            Path readme = inputFolder.resolve("README.txt");
            Files.writeString(readme, """
                Retromod input folder

                Put old mod jars directly in this folder. Retromod updates them,
                installs the results in mods/, and keeps the originals in processed/.

                On Fabric, do not put an old mod straight in mods/. Fabric may reject
                its version before Retromod gets a chance to update it.

                Help and bug reports:
                https://github.com/Bownlux/Retromod/issues
                """);
        } catch (IOException e) {
            LOGGER.debug("Could not create README", e);
        }
    }
    
    /** Register a transformed mod for health monitoring. */
    public static void registerTransformedMod(String modId, String modName,
                                               Path transformedPath, Path originalPath,
                                               Path gameDir) {

        Path backupPath = null;
        try {
            Path backupFolder = gameDir.resolve(BACKUP_FOLDER);
            Files.createDirectories(backupFolder);
            
            String backupName = originalPath.getFileName().toString()
                .replace(".jar", "-original.jar");
            backupPath = backupFolder.resolve(backupName);
            
            if (!Files.exists(backupPath)) {
                Files.copy(originalPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.debug("Created backup: {}", backupPath.getFileName());
            }
        } catch (IOException e) {
            LOGGER.warn("Could not create backup for {}", modId);
        }
        
        ModHealthInfo info = new ModHealthInfo(
            modId, modName, transformedPath, backupPath,
            System.currentTimeMillis(), true
        );
        
        monitoredMods.put(modId, info);
        modErrors.put(modId, new CopyOnWriteArrayList<>());
        
        LOGGER.debug("Monitoring mod health: {}", modId);
    }
    
    /** Record an error caught from a mod; marks it broken past the threshold. */
    public static void reportModError(String modId, String errorMessage, Throwable error) {
        List<String> errors = modErrors.computeIfAbsent(modId, k -> new CopyOnWriteArrayList<>());

        String fullError = errorMessage + ": " +
            (error != null ? error.getClass().getSimpleName() + " - " + error.getMessage() : "Unknown");

        errors.add(fullError);

        LOGGER.warn("Mod error reported for {}: {}", modId, fullError);

        if (errors.size() >= ERROR_THRESHOLD) {
            markModAsBroken(modId, errors);
        }
    }

    /** Attribute an error to a mod by matching its id against the class name. */
    public static void reportErrorByClass(String className, Throwable error) {
        for (Map.Entry<String, ModHealthInfo> entry : monitoredMods.entrySet()) {
            String modId = entry.getKey();
            if (className.toLowerCase().contains(modId.toLowerCase())) {
                reportModError(modId, "Error in " + className, error);
                return;
            }
        }

        LOGGER.warn("Error in unknown mod class {}: {}", className,
            error != null ? error.getMessage() : "Unknown");
    }

    private static void markModAsBroken(String modId, List<String> errors) {
        ModHealthInfo info = monitoredMods.get(modId);
        if (info == null || !info.isHealthy()) return;

        monitoredMods.put(modId, new ModHealthInfo(
            info.modId(), info.modName(), info.transformedPath(),
            info.backupPath(), info.transformTime(), false
        ));
        
        LOGGER.error("Retromod saw repeated errors from {} ({}). This may be a compatibility issue.",
            info.modName(), modId);
        LOGGER.error("Recent errors:");
        for (String err : errors) {
            LOGGER.error("  - {}", err);
        }
        LOGGER.error("Report the issue at https://github.com/Bownlux/Retromod/issues");

        if (EnvironmentDetector.canShowGui()) {
            showBrokenModDialog(info, errors);
        }
    }

    private static void showBrokenModDialog(ModHealthInfo info, List<String> errors) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            
            StringBuilder message = new StringBuilder();
            message.append("Retromod saw repeated errors from ")
                .append(info.modName()).append(".\n\n")
                .append("The game launched, but this mod may not be working correctly.\n")
                .append("You can report the issue or restore its backup.\n\n");
            message.append("Recent errors:\n");
            for (int i = 0; i < Math.min(3, errors.size()); i++) {
                message.append("- ").append(truncate(errors.get(i), 80)).append("\n");
            }
            
            int choice = JOptionPane.showOptionDialog(
                null,
                message.toString(),
                "Retromod found repeated mod errors",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                new String[]{"Report Issue", "Restore Original", "Ignore"},
                "Report Issue"
            );
            
            if (choice == 0) {
                openGitHub();
            } else if (choice == 1) {
                restoreOriginal(info);
            }
        });
    }

    /** Delete the broken transformed jar and put the backup back in retromod-input/ for a retry. */
    public static boolean restoreOriginal(ModHealthInfo info) {
        if (info.backupPath() == null || !Files.exists(info.backupPath())) {
            LOGGER.error("No backup available for {}", info.modId());
            return false;
        }

        try {
            Path inputFolder = info.transformedPath().getParent().getParent().resolve("retromod-input");
            Files.createDirectories(inputFolder);
            Path destination = inputFolder.resolve(
                info.backupPath().getFileName().toString().replace("-original.jar", ".jar")
            );

            Path staged = Files.createTempFile(inputFolder, ".retromod-restore-", ".tmp");
            try {
                Files.copy(info.backupPath(), staged, StandardCopyOption.REPLACE_EXISTING);
                moveReplacing(staged, destination);
            } finally {
                Files.deleteIfExists(staged);
            }

            if (Files.exists(info.transformedPath())) {
                Files.delete(info.transformedPath());
                LOGGER.info("Removed the broken transformed copy: {}",
                    info.transformedPath().getFileName());
            }
            LOGGER.info("Restored the original to retromod-input: {}", destination.getFileName());
            
            JOptionPane.showMessageDialog(
                null,
                "The transformed jar was removed and the original was copied to retromod-input/.\n\n" +
                "Restart Minecraft to let Retromod try it again. If it still fails, attach\n" +
                "logs/latest.log to a GitHub issue.",
                "Backup restored",
                JOptionPane.INFORMATION_MESSAGE
            );
            
            return true;
            
        } catch (IOException e) {
            LOGGER.error("Could not restore original for {}", info.modId(), e);
            return false;
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    
    /** Open GitHub Issues via the Desktop API (no Runtime.exec). */
    private static void openGitHub() {
        try {
            String url = "https://github.com/Bownlux/Retromod/issues/new?title=Bug%20Report%20-%20Mod%20Not%20Working";
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(java.net.URI.create(url));
            } else {
                LOGGER.warn("Desktop API not available - please visit {} manually", url);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not open browser");
        }
    }
    
    public static boolean isModHealthy(String modId) {
        ModHealthInfo info = monitoredMods.get(modId);
        return info == null || info.isHealthy();
    }

    public static List<ModHealthInfo> getBrokenMods() {
        return monitoredMods.values().stream()
            .filter(info -> !info.isHealthy())
            .toList();
    }

    /** Clear a mod's error history, e.g. after a restart. */
    public static void clearErrors(String modId) {
        modErrors.remove(modId);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /** Sanity-check a transformed jar: non-empty, has mod metadata, size in range. */
    public static boolean validateTransformation(Path originalJar, Path transformedJar) {
        try {
            try (JarFile jar = new JarFile(transformedJar.toFile())) {
                if (!jar.entries().hasMoreElements()) {
                    LOGGER.warn("The transformed jar is empty");
                    return false;
                }

                if (jar.getEntry("fabric.mod.json") == null &&
                    jar.getEntry("META-INF/mods.toml") == null) {
                    LOGGER.warn("The transformed jar has no supported mod metadata");
                    return false;
                }
            }

            long originalSize = Files.size(originalJar);
            long transformedSize = Files.size(transformedJar);

            // size drift past 50% is suspect but not fatal
            if (transformedSize < originalSize * 0.5 || transformedSize > originalSize * 2) {
                LOGGER.warn("Transformed JAR size differs significantly: {} -> {}",
                    originalSize, transformedSize);
            }

            return true;

        } catch (Exception e) {
            LOGGER.error("Transformation validation failed", e);
            return false;
        }
    }
}
