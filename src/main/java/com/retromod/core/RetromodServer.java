/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.util.RetromodLog;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Fabric entry point for a headless dedicated server. */
@Environment(EnvType.SERVER)
public class RetromodServer implements DedicatedServerModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Server");

    @Override
    public void onInitializeServer() {
        LOGGER.debug(RetromodLog.DETAIL, "Starting Retromod on the dedicated server.");

        EnvironmentDetector.setEnvironment(false, true);

        initializeServerFeatures();
        registerServerCrashHandler();

        LOGGER.debug(RetromodLog.DETAIL, "Server mode is ready. Bytecode transforms and ahead-of-time compilation are enabled.");
        LOGGER.debug(RetromodLog.DETAIL, "GUI features are unavailable on a headless server, so warnings will appear here.");
    }

    private void initializeServerFeatures() {
        Path gameDir = Paths.get(".").toAbsolutePath().normalize();
        Path modsFolder = gameDir.resolve("mods");

        // Keep the server folders usable even when the pre-launch entry point did not run.
        ModHealthChecker.ensureFoldersExist(gameDir);

        try {
            HybridTransformationEngine hybrid = HybridTransformationEngine.getInstance();
            hybrid.initialize(modsFolder, RetromodVersion.TARGET_MC_VERSION);
            LOGGER.debug(RetromodLog.DETAIL, "The server transform engine is ready.");
        } catch (Exception e) {
            LOGGER.warn("Could not initialize hybrid engine: {}", e.getMessage());
        }

        try (java.util.stream.Stream<Path> s = java.nio.file.Files.list(modsFolder)) {
            long modCount = s
                .filter(p -> p.toString().endsWith(".jar"))
                .count();
            LOGGER.debug(RetromodLog.DETAIL, "Found {} mod JARs in mods folder", modCount);
        } catch (Exception e) {
            LOGGER.debug("Could not count mods: {}", e.getMessage());
        }
    }

    private void registerServerCrashHandler() {
        try {
            SafeCrashHandler.getInstance();
            if (EnvironmentDetector.classExists("net.minecraft.server.MinecraftServer")) {
                LOGGER.debug("The crash handler is ready for the server instance.");
            } else {
                LOGGER.debug("MinecraftServer is not available yet. Crash registration will wait.");
            }
        } catch (Exception e) {
            LOGGER.debug("Crash handler not available: {}", e.getMessage());
        }
    }

    /** Registers the server with the crash handler after startup. */
    public static void onServerStarted(Object server) {
        try {
            SafeCrashHandler.getInstance().registerServer(server);
            LOGGER.debug(RetromodLog.DETAIL, "The crash handler is attached to the Minecraft server.");
        } catch (Exception e) {
            LOGGER.debug("Could not register server with crash handler");
        }
    }

    /** Updates server performance monitoring once per tick. */
    public static void onServerTick() {
        try {
            MemorySafetyMonitor.getInstance().onServerTick();
        } catch (Exception e) {
            // Monitoring must never interrupt the server tick.
        }
    }
}
