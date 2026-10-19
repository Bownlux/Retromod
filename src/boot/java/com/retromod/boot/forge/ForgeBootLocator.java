/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.boot.forge;

import com.retromod.boot.BootCore;
import com.retromod.boot.BootLog;
import com.retromod.boot.InPlaceBoot;
import com.retromod.boot.LoaderHooks;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Starts Retromod on Forge 1.20 to 26.3 and NeoForge 1.20.1 through the forgespi locator SPI. */
public final class ForgeBootLocator implements IModLocator {

    /** Must match {@code Retromod-Boot-Generation} in the jars built for these hosts. */
    public static final String GENERATION = "A";

    private final BootLog log = BootLog.create();
    private volatile String hostMinecraft;
    private volatile String hostLoaderBuild;

    @Override
    public String name() {
        return "retromod";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
        // Keep the host versions FML passes in, for scanMods.
        Object minecraft = arguments == null ? null : arguments.get("mcVersion");
        hostMinecraft = minecraft == null ? null : minecraft.toString();
        Object forge = arguments == null ? null : arguments.get("forgeVersion");
        hostLoaderBuild = forge == null ? null : forge.toString();
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
            Path gameDir = LoaderHooks.fmlPath(LoaderHooks.FORGE_FML_PATHS, "GAMEDIR");
            Path modsDir = LoaderHooks.fmlPath(LoaderHooks.FORGE_FML_PATHS, "MODSDIR");
            Optional<BootCore.Prepared> prepared = BootCore.prepare(gameDir, modsDir, GENERATION,
                    hostMinecraft, ForgeBootLocator.class, System.getProperties(), log);
            if (prepared.isEmpty()) return List.of();

            // FML claims the jar that declares this service for its service layer and never loads it
            // as a mod. So hand FML the runtime nested inside the jar as an ordinary mod file, and
            // RetromodForge constructs as before.
            Object runtimeLocator = LoaderHooks.buildFolderLocator(LoaderHooks.FORGE_SPI,
                    prepared.get().runtimeDirectory(), "retromod runtime");
            List<Object> located = new ArrayList<>(((IModLocator) runtimeLocator).scanMods());
            log.info("Loading Retromod " + prepared.get().source().version() + " from "
                    + BootCore.displayPath(gameDir, prepared.get().runtimeJar()));
            // Served copies call into Retromod's runtime, so they only go in beside it.
            if (!located.isEmpty()) {
                located.addAll(applyInPlace(prepared.get(), gameDir, modsDir));
            }
            return located;
        } catch (Throwable failure) {
            // A bootstrap failure must never stop the loader. The game starts without Retromod.
            log.error("Retromod could not start: " + LoaderHooks.unwrap(failure)
                    + ". Retromod is inactive for this launch. Please report this with "
                    + "logs/latest.log.");
            return List.of();
        }
    }

    /** Hides old mods from FML's mods folder scan and returns mod files for their cached translations. */
    private List<?> applyInPlace(BootCore.Prepared prepared, Path gameDir, Path modsDir) {
        // The files in mods/ are never changed. Any failure here leaves the runtime loaded and the
        // mods folder as FML would see it without in-place loading.
        Optional<InPlaceBoot.Decision> decision = InPlaceBoot.run(prepared, gameDir, modsDir,
                hostMinecraft, hostLoaderBuild, InPlaceBoot.FORGE_TOML, "folder", log);
        if (decision.isEmpty()) return List.of();
        try {
            LoaderHooks.hideFromModsFolder(LoaderHooks.FORGE_FML_LOADING, decision.get().hide());
        } catch (Throwable failure) {
            // Without the list, both files stay visible. FML keeps one file per mod id, the
            // first found on equal versions, which is the copy this locator returns first.
            log.warn("Could not keep original mods out of the mod list ("
                    + LoaderHooks.unwrap(failure) + "). Forge chooses between each original "
                    + "and its translation by version. Please report this with "
                    + "logs/latest.log.");
        }
        // The translated copies come from FML's own folder locator over the cache folder.
        Path serveFolder = decision.get().serveFolder();
        if (serveFolder == null) return List.of();
        try {
            Object cacheLocator = LoaderHooks.buildFolderLocator(LoaderHooks.FORGE_SPI,
                    serveFolder, "retromod cache");
            return ((IModLocator) cacheLocator).scanMods();
        } catch (Throwable failure) {
            log.error("Could not load Retromod's translated mods from " + serveFolder + ": "
                    + LoaderHooks.unwrap(failure) + ". Please report this with logs/latest.log.");
            return List.of();
        }
    }

    @Override
    public String toString() {
        return name();
    }
}
