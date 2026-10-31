/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.boot.neoforge;

import com.retromod.boot.BootCore;
import com.retromod.boot.BootLog;
import com.retromod.boot.LoaderHooks;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModLocator;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Starts Retromod on NeoForge 20.2 to 20.4 and on builds with FML 3.0.18 (all of 20.5, early 20.6) through the neoforgespi folder-locator SPI. */
public final class LegacyNeoForgeBootLocator implements IModLocator {

    /** Must match {@code Retromod-Boot-Generation} in the jars built for these hosts. */
    public static final String GENERATION = "A-neo";

    private final BootLog log = BootLog.create();
    private volatile String hostMinecraft;

    @Override
    public String name() {
        return "retromod";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
        Object minecraft = arguments == null ? null : arguments.get("mcVersion");
        hostMinecraft = minecraft == null ? null : minecraft.toString();
    }

    @Override
    public void scanFile(IModFile modFile, Consumer<Path> pathConsumer) {
        // The runtime is scanned by the folder locator that created its mod file.
    }

    @Override
    public boolean isValid(IModFile modFile) {
        return true;
    }

    @Override
    public List<?> scanMods() {
        try {
            // Read the game folders, then pick the Retromod runtime to load.
            Path gameDir = LoaderHooks.fmlPath(LoaderHooks.NEOFORGE_FML_PATHS, "GAMEDIR");
            Path modsDir = LoaderHooks.fmlPath(LoaderHooks.NEOFORGE_FML_PATHS, "MODSDIR");
            Optional<BootCore.Prepared> prepared = BootCore.prepare(gameDir, modsDir, GENERATION,
                    hostMinecraft, LegacyNeoForgeBootLocator.class, System.getProperties(), log);
            if (prepared.isEmpty()) return List.of();

            // Same job as the Forge locator under NeoForge's package names: hand FML the runtime
            // nested inside this jar as an ordinary mod file.
            Object runtimeLocator = LoaderHooks.buildFolderLocator(LoaderHooks.NEOFORGE_SPI,
                    prepared.get().runtimeDirectory(), "retromod runtime");
            List<?> located = ((IModLocator) runtimeLocator).scanMods();
            log.info("Loading Retromod " + prepared.get().source().version() + " from "
                    + BootCore.displayPath(gameDir, prepared.get().runtimeJar()));
            return located;
        } catch (Throwable failure) {
            // A bootstrap failure must never stop the loader. The game starts without Retromod.
            log.error("Retromod could not start: " + LoaderHooks.unwrap(failure)
                    + ". Retromod is inactive for this launch. Please report this with "
                    + "logs/latest.log.");
            return List.of();
        }
    }

    @Override
    public String toString() {
        return name();
    }
}
