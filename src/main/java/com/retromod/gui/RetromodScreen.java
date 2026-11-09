/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.core.*;
import com.retromod.util.ArchivePublication;
import com.retromod.util.McReflect;
import com.retromod.util.RetromodLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Updates mods picked in the native file picker or dropped on the screen, and shows the results. */
public class RetromodScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Screen");
    // ConfirmScreen stacks its text above the buttons, so a long list pushes them off screen.
    private static final int MAX_LISTED_MODS = 10;
    // One update at a time: two quick drops would share the transformer's per-mod state.
    private static final Object UPDATES = new Object();

    // Minecraft is not on the compile classpath, so its UI is reached through reflection.
    private final Object minecraftClient;
    // The screen that the results and the folder fallback return to.
    private final Object parentScreen;

    public record ModResult(String modName, Status status, String message, boolean inPlace) {
        public ModResult(String modName, Status status, String message) {
            this(modName, status, message, false);
        }

        public enum Status { UPDATED, UNCHANGED, FAILED }
    }

    public RetromodScreen(Object client) {
        this(client, null);
    }

    public RetromodScreen(Object client, Object parentScreen) {
        this.minecraftClient = client;
        this.parentScreen = parentScreen;
    }

    /** Opens the picker and updates the chosen mods. */
    public void open() {
        Path gameDir = getGameDir();
        NativeFilePicker.open(this::runOnClientThread, LastPickedFolder.startFolder(gameDir),
                new NativeFilePicker.Listener() {
            @Override
            public void chosen(List<File> jars) {
                if (handleChosen(jars)) {
                    LastPickedFolder.remember(gameDir, jars.get(0).getAbsoluteFile().getParentFile());
                }
            }

            @Override
            public void unavailable(String reason) {
                LOGGER.warn("Retromod could not show a file picker: {}. It opens retromod-input/ "
                        + "instead.", reason);
                showInputFolderInstead();
            }
        });
    }

    /** Updates jars dropped on the Retromod screen exactly like picked ones. */
    public void takeDropped(List<Path> dropped) {
        List<String> names = new ArrayList<>();
        for (Path path : dropped) names.add(path.toString());
        List<File> jars = NativeFilePicker.keepJars(names);
        if (jars.isEmpty()) {
            LOGGER.info("Nothing dropped on the Retromod screen was a .jar file, so Retromod "
                    + "updated nothing.");
            return;
        }
        // Minecraft hands drops over on its main thread, and updating mods takes a while.
        Thread worker = new Thread(() -> handleChosen(jars), "Retromod-Drop");
        worker.setDaemon(true);
        worker.start();
    }

    /** Updates the jars, or names the ones it cannot read and shows retromod-input/; false then. */
    boolean handleChosen(List<File> jars) {
        // A launcher sandbox can show a file it does not let the game read.
        List<String> unreadable = unreadableNames(jars);
        if (!unreadable.isEmpty()) {
            LOGGER.warn("Retromod cannot read {} where it is, so it updated none of the chosen "
                    + "jars. Put them in retromod-input/ and restart Minecraft.",
                    String.join(", ", unreadable));
            runOnClientThread(() -> MainScreenFactory.showUnreadableInstead(parentScreen, unreadable));
            return false;
        }
        updateAndShowResults(jars);
        return true;
    }

    /** The names of the jars Retromod cannot open for reading, in their order. */
    static List<String> unreadableNames(List<File> jars) {
        List<String> unreadable = new ArrayList<>();
        for (File jar : jars) {
            // Opening is the real test: a sandbox can deny a file that looks readable.
            try (java.io.InputStream in = Files.newInputStream(jar.toPath())) {
                in.read();
            } catch (java.io.IOException | RuntimeException denied) {
                unreadable.add(jar.getName());
            }
        }
        return unreadable;
    }

    /** Updates the chosen mods on a worker thread and shows the results. */
    private void updateAndShowResults(List<File> jars) {
        synchronized (UPDATES) {
            try {
                showResults(updateMods(getGameDir(), jars.toArray(new File[0])));
            } catch (RuntimeException e) {
                LOGGER.error("Could not update the selected mods", e);
            }
        }
    }

    /** Opens retromod-input/ and names it on screen, for when no file picker can open. */
    void showInputFolderInstead() {
        runOnClientThread(() -> MainScreenFactory.showInputFolderInstead(parentScreen, true));
    }

    /** Runs a task on Minecraft's main thread, the only thread that may change screens. */
    void runOnClientThread(Runnable task) {
        // Minecraft is a BlockableEventLoop, which implements Executor under every mapping.
        if (minecraftClient instanceof Executor client) {
            client.execute(task);
        } else {
            task.run();
        }
    }

    /** Updates the selected mods and installs the resulting jars. Every picked mod is tried. */
    static List<ModResult> updateMods(Path gameDir, File[] modFiles) {
        List<ModResult> results = new ArrayList<>();
        Path modsFolder = gameDir.resolve("mods");
        ModCompatibilityChecker checker = new ModCompatibilityChecker(gameDir);
        ModComplexityAnalyzer complexityAnalyzer = new ModComplexityAnalyzer();

        try {
            Files.createDirectories(modsFolder);
        } catch (Exception e) {
            LOGGER.error("Could not create the mods folder at {}", modsFolder, e);
            return List.of(new ModResult(
                modsFolder.toString(), ModResult.Status.FAILED, e.getMessage()));
        }

        for (File modFile : modFiles) {
            String name = modFile.getName();
            LOGGER.debug(RetromodLog.DETAIL, "Checking {}", name);

            try {
                Path modPath = modFile.toPath();

                // The scan only writes what it found to the debug log, for bug reports.
                complexityAnalyzer.analyze(modPath);

                // An outdated Forge or NeoForge mod goes in unchanged when in-place loading is on;
                // otherwise an outdated mod is transformed, and a current one is copied as is.
                ModCompatibilityChecker.IncompatibleMod analysis = checker.analyzeJar(modPath);
                Optional<ModCompatibilityChecker.InPlaceInstall> inPlace = analysis != null
                        ? checker.installInPlace(modPath) : Optional.empty();

                if (inPlace.isPresent()) {
                    boolean failed = inPlace.get().outcome()
                            == com.retromod.inplace.InPlaceLaunch.ChosenOutcome.FAILED;
                    results.add(new ModResult(name,
                        failed ? ModResult.Status.FAILED : ModResult.Status.UPDATED,
                        inPlaceResultLine(name, inPlace.get().outcome()), true));
                } else if (analysis != null) {
                    Path transformed = checker.transformAndInstall(modPath);
                    results.add(new ModResult(name, ModResult.Status.UPDATED,
                        "Updated as " + transformed.getFileName()));
                    LOGGER.info("Updated {} as {}", name, transformed.getFileName());
                } else {
                    ArchivePublication.copyReplacing(modPath, modsFolder.resolve(name));
                    results.add(new ModResult(name, ModResult.Status.UNCHANGED,
                        "No changes needed"));
                    LOGGER.info("Copied {} to mods/ unchanged because it needs no update", name);
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

    /** Shows a short result summary inside Minecraft, from any thread. */
    void showResults(List<ModResult> results) {
        String text = resultsText(results);
        boolean restart = needsRestart(results);
        runOnClientThread(() -> InGameScreenFactory.showUpdateResults(parentScreen, text, restart));
    }

    /** The counts, one line per mod with the reason under problems, and the closing question. */
    static String resultsText(List<ModResult> results) {
        List<String> lines = new ArrayList<>();
        addCount(lines, "Updated: ", results, ModResult.Status.UPDATED);
        addCount(lines, "No changes needed: ", results, ModResult.Status.UNCHANGED);
        addCount(lines, "Failed: ", results, ModResult.Status.FAILED);
        lines.add("");

        int listed = Math.min(results.size(), MAX_LISTED_MODS);
        for (ModResult r : results.subList(0, listed)) {
            String prefix = switch (r.status()) {
                case UPDATED -> "Updated:";
                case UNCHANGED -> "Unchanged:";
                case FAILED -> "Failed:";
            };
            lines.add(prefix + " " + r.modName());
            if (r.status() == ModResult.Status.FAILED || r.inPlace()) {
                lines.add("  " + r.message());
            }
        }
        if (results.size() > listed) {
            lines.add("and " + (results.size() - listed)
                    + " more. logs/latest.log lists every result.");
        }

        // Closing the game only helps when a jar now waits in mods/ for the next launch.
        if (needsRestart(results)) {
            lines.add("");
            lines.add("Close Minecraft now? Relaunch it from your launcher to load these mods.");
        }
        return String.join("\n", lines);
    }

    /** One result line for a jar placed in mods/ by in-place loading. */
    static String inPlaceResultLine(String fileName,
            com.retromod.inplace.InPlaceLaunch.ChosenOutcome outcome) {
        return switch (outcome) {
            case TRANSLATED -> "Added " + fileName
                    + " to mods. Its translated copy loads after a restart.";
            case ALREADY_TRANSLATED -> "Added " + fileName
                    + " to mods. Retromod already has a translated copy.";
            case NEXT_LAUNCH -> "Added " + fileName + " to mods. Retromod translates it on "
                    + "the next launch and then asks for one more restart.";
            case FAILED -> fileName + " could not be translated. logs/latest.log has the reason.";
        };
    }

    /** True when an updated or unchanged jar now waits in mods/ for the next launch. */
    static boolean needsRestart(List<ModResult> results) {
        return results.stream().anyMatch(r -> r.status() == ModResult.Status.UPDATED
                || r.status() == ModResult.Status.UNCHANGED);
    }

    private static void addCount(List<String> lines, String label, List<ModResult> results,
            ModResult.Status status) {
        long count = results.stream().filter(r -> r.status() == status).count();
        if (count > 0) lines.add(label + count);
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
}
