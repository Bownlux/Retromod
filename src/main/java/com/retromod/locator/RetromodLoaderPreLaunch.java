/*
 * Retromod - Fabric pre-launch for the standalone CurseForge "Retromod Loader" stub.
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.locator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Fabric pre-launch entry point for the standalone CurseForge "Retromod Loader" stub jar (#78).
 *
 * <p>The stub is a CF-hosted jar that a pack places in {@code mods/}; Retromod and the
 * transformed {@code *-retromod.jar} outputs ride in {@code mods/Retromod/} as pack overrides,
 * which CF export allows where non-CF jars in {@code mods/} are rejected.
 *
 * <p>Fabric has no mod-file locator SPI and pre-launch runs after Knot's mod scan, so jars in
 * {@code mods/Retromod/} can't be loaded in place. This moves the loader-ready jars into
 * {@code mods/} and relies on a one-time restart, after which Retromod takes over. It mirrors
 * {@code RetromodPreLaunch.drainReadyModsFolder}, duplicated so the stub jar stays self-contained
 * (JDK + SLF4J + Fabric API only, no transform engine).
 *
 * <p>On NeoForge the stub uses {@link RetromodModLocator} instead, which loads in place with no
 * restart. Both ship in the one stub jar.
 */
public final class RetromodLoaderPreLaunch implements PreLaunchEntrypoint {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Loader");
    private static final String SUBFOLDER = "Retromod";

    @Override
    public void onPreLaunch() {
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            if (gameDir == null) {
                gameDir = Path.of(".");
            }
            Path mods = gameDir.resolve("mods");
            Path folder = mods.resolve(SUBFOLDER);
            if (!Files.isDirectory(folder)) {
                return;
            }
            // Fabric can load this folder directly when fabric.addMods points to it.
            if (addModsCovers(gameDir, folder)) {
                LOGGER.info("Fabric is loading transformed mods directly from {}", folder);
                return;
            }
            List<Path> jars = listJars(folder);
            if (jars.isEmpty()) {
                return;
            }
            int moved = 0;
            for (Path jar : jars) {
                try {
                    Files.move(jar, mods.resolve(jar.getFileName().toString()),
                            StandardCopyOption.REPLACE_EXISTING);
                    moved++;
                } catch (IOException e) {
                    LOGGER.error("Could not move {} into the mods folder: {}",
                            jar.getFileName(), e.toString());
                }
            }
            if (moved > 0) {
                LOGGER.info("Moved {} transformed mod {} into the mods folder. Restart "
                                + "Minecraft to load {}.",
                        moved, moved == 1 ? "jar" : "jars", moved == 1 ? "it" : "them");
            }
        } catch (Exception e) {
            LOGGER.error("Could not prepare transformed mods before launch: {}", e.toString());
        }
    }

    private static boolean addModsCovers(Path gameDir, Path folder) {
        String prop = System.getProperty("fabric.addMods");
        if (prop == null || prop.isBlank()) {
            return false;
        }
        Path target;
        try {
            target = folder.toAbsolutePath().normalize();
        } catch (Exception e) {
            return false;
        }
        for (String entry : prop.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path p = Path.of(entry.trim());
                if (!p.isAbsolute()) {
                    p = gameDir.resolve(p);
                }
                if (p.toAbsolutePath().normalize().equals(target)) {
                    return true;
                }
            } catch (Exception ignored) {
                // One malformed path should not hide the other configured folders.
            }
        }
        return false;
    }

    private static List<Path> listJars(Path folder) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder)) {
            s.filter(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
             .sorted()
             .forEach(out::add);
        } catch (IOException e) {
            LOGGER.error("Could not list transformed mods in {}: {}", folder, e.toString());
        }
        return out;
    }
}
