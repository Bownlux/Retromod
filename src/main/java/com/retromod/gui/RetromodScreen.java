/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.core.*;
import com.retromod.util.ArchivePublication;
import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Opens the native file picker from Minecraft and reports the update results
 * back in game. Minecraft UI calls use reflection because it is not on the
 * compile classpath.
 */
public class RetromodScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Screen");

    private final Object minecraftClient;

    public record ModResult(String modName, Status status, String message) {
        public enum Status { UPDATED, UNCHANGED, FAILED, RISKY }
    }

    public RetromodScreen(Object client) {
        this.minecraftClient = client;
    }

    /** Opens the picker and updates the chosen mods. */
    public void open() {
        // The native picker must not pause Minecraft's render thread.
        CompletableFuture.runAsync(() -> {
            try {
                File[] selectedFiles = showNativeFilePicker();

                if (selectedFiles != null && selectedFiles.length > 0) {
                    List<ModResult> results = updateMods(selectedFiles);
                    showResults(results);
                }
            } catch (Exception e) {
                LOGGER.error("Could not update the selected mods", e);
            }
        });
    }

    /** Shows the operating system's file picker. */
    private File[] showNativeFilePicker() throws Exception {
        final File[][] result = {null};

        java.awt.EventQueue.invokeAndWait(() -> {
            Frame frame = new Frame();
            frame.setUndecorated(true);
            frame.setVisible(false);

            FileDialog dialog = new FileDialog(frame, "Retromod: choose mod jars", FileDialog.LOAD);
            dialog.setDirectory(System.getProperty("user.home"));
            dialog.setMultipleMode(true);

            dialog.setFilenameFilter((dir, name) -> name.toLowerCase().endsWith(".jar"));

            dialog.setVisible(true);

            File[] files = dialog.getFiles();
            if (files != null && files.length > 0) {
                result[0] = files;
            }

            dialog.dispose();
            frame.dispose();
        });

        return result[0];
    }

    /** Updates the selected mods and installs the resulting jars. */
    private List<ModResult> updateMods(File[] modFiles) {
        List<ModResult> results = new ArrayList<>();
        Path gameDir = getGameDir();
        Path modsFolder = gameDir.resolve("mods");
        ModCompatibilityChecker checker = new ModCompatibilityChecker(gameDir);
        ModComplexityAnalyzer complexityAnalyzer = new ModComplexityAnalyzer();
        boolean forceComplex = isForceTranslateEnabled(gameDir);

        try {
            Files.createDirectories(modsFolder);
        } catch (Exception e) {
            LOGGER.error("Could not create the mods folder at {}", modsFolder, e);
            return List.of(new ModResult(
                modsFolder.toString(), ModResult.Status.FAILED, e.getMessage()));
        }

        for (File modFile : modFiles) {
            String name = modFile.getName();
            LOGGER.info("Checking {}", name);

            try {
                Path modPath = modFile.toPath();

                ModComplexityAnalyzer.ComplexityReport report = complexityAnalyzer.analyze(modPath);

                if (report.isUnlikelyToWork() && !forceComplex) {
                    LOGGER.warn("Skipped {} because its compatibility score is {}: {}",
                        name, report.score(), report.reason());
                    results.add(new ModResult(
                        name,
                        ModResult.Status.RISKY,
                        "High compatibility risk: " + report.reason() +
                            ". Turn on \"Try unlikely mods\" in Settings to test it anyway."
                    ));
                    continue;
                }

                ModCompatibilityChecker.IncompatibleMod analysis = checker.analyzeJar(modPath);

                if (analysis != null) {
                    Path transformed = checker.transformAndInstall(modPath);
                    results.add(new ModResult(name, ModResult.Status.UPDATED,
                        "Updated as " + transformed.getFileName()));
                    LOGGER.info("Updated {} as {}", name, transformed.getFileName());
                } else {
                    ArchivePublication.copyReplacing(modPath, modsFolder.resolve(name));
                    results.add(new ModResult(name, ModResult.Status.UNCHANGED,
                        "No changes needed"));
                }

            } catch (Exception e) {
                LOGGER.error("Could not update {}", name, e);
                results.add(new ModResult(name, ModResult.Status.FAILED,
                    e.getClass().getSimpleName() + ": " +
                        (e.getMessage() != null ? e.getMessage() : "unknown error")));
            }
        }

        return results;
    }

    /** Shows a short result summary inside Minecraft. */
    private void showResults(List<ModResult> results) {
        long updated = results.stream().filter(r -> r.status() == ModResult.Status.UPDATED).count();
        long failed = results.stream().filter(r -> r.status() == ModResult.Status.FAILED).count();
        long unchanged = results.stream().filter(r -> r.status() == ModResult.Status.UNCHANGED).count();
        long risky = results.stream().filter(r -> r.status() == ModResult.Status.RISKY).count();

        List<String> resultLines = new ArrayList<>();

        if (updated > 0) resultLines.add("Updated: " + updated);
        if (unchanged > 0) resultLines.add("No changes needed: " + unchanged);
        if (risky > 0) resultLines.add("Skipped for review: " + risky);
        if (failed > 0) resultLines.add("Failed: " + failed);

        resultLines.add("");

        for (ModResult r : results) {
            String prefix = switch (r.status()) {
                case UPDATED -> "Updated:";
                case UNCHANGED -> "Unchanged:";
                case FAILED -> "Failed:";
                case RISKY -> "Review:";
            };
            resultLines.add(prefix + " " + r.modName());
            if (r.status() == ModResult.Status.RISKY) {
                resultLines.add("  " + r.message());
            }
        }

        InGameScreenFactory.showUpdateResults(resultLines, updated > 0);
    }

    /** Finds the game directory through the active loader or client. */
    private Path getGameDir() {
        // Prefer the client because it works without linking a loader API.
        java.lang.reflect.Field dirField = McReflect.findField(
            minecraftClient.getClass(),
            "runDirectory", "gameDirectory"
        );
        if (dirField != null) {
            try {
                Object dir = dirField.get(minecraftClient);
                if (dir instanceof File f) return f.toPath();
                if (dir instanceof Path p) return p;
            } catch (Exception ignored) {}
        }

        try {
            Class<?> fabricLoader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = fabricLoader.getMethod("getInstance").invoke(null);
            return (Path) fabricLoader.getMethod("getGameDir").invoke(instance);
        } catch (Exception ignored) {}

        try {
            Class<?> fmlPaths = Class.forName("net.neoforged.fml.loading.FMLPaths");
            Object gameDirPath = fmlPaths.getMethod("getOrCreateGameRelativePath",
                Path.class).invoke(null, Path.of("."));
            if (gameDirPath instanceof Path p) return p;
        } catch (Exception ignored) {}

        return Path.of(".").toAbsolutePath().normalize();
    }

    /** Reads the opt-in setting for high-risk mods. */
    private boolean isForceTranslateEnabled(Path gameDir) {
        Path configPath = gameDir.resolve("config/retromod/config.json");
        return RetromodConfig.getBooleanIfPresent(
                configPath, "force_translate_complex", false);
    }
}
