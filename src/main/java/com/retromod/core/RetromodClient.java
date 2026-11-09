/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.retromod.gui.TitleScreenButtonInjector;
import com.retromod.util.McReflect;
import com.retromod.util.RetromodLog;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Fabric client setup for the title-screen tools and crash handler. */
@Environment(EnvType.CLIENT)
public class RetromodClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Client");

    @Override
    public void onInitializeClient() {
        LOGGER.debug(RetromodLog.DETAIL, "Starting Retromod on the client.");

        EnvironmentDetector.setEnvironment(true, false);
        // Old mods' item properties reach their item definitions through a property type added before the first reload.
        com.retromod.shim.common.LegacyItemPropertiesBridge.addPropertyType();

        Path gameDir = Paths.get(".").toAbsolutePath().normalize();

        // Keep setup usable even when the pre-launch entry point did not run.
        ModHealthChecker.ensureFoldersExist(gameDir);

        initializeGui();
        registerClientCrashHandler();

        LOGGER.debug(RetromodLog.DETAIL, "Retromod client setup is ready.");
    }
    
    /** Adds the title screen button (loader auto-detected); needs Fabric API on Fabric. */
    private void initializeGui() {
        try {
            // register() logs why the button is missing, so this only points to the other ways in.
            if (TitleScreenButtonInjector.register()) {
                LOGGER.debug(RetromodLog.DETAIL, "Retromod is available from the title screen.");
            } else {
                LOGGER.info("Retromod has no title screen button in this game. Put old mods in "
                        + "retromod-input/, or use the CLI.");
            }
        } catch (Exception e) {
            LOGGER.warn("Could not register title screen button: {}", e.getMessage());
            LOGGER.info("Use the CLI instead: retromod <command>");
        }
    }
    
    /** Hands the MC client instance to the crash handler for world saving. */
    private void registerClientCrashHandler() {
        try {
            SafeCrashHandler crashHandler = SafeCrashHandler.getInstance();

            Class<?> mcClass = McReflect.findClass(
                "net.minecraft.client.MinecraftClient",  // yarn
                "net.minecraft.client.Minecraft"         // mojang
            );

            if (mcClass != null) {
                Method getInstance = McReflect.findMethod(mcClass, "getInstance");
                if (getInstance != null) {
                    Object mcInstance = getInstance.invoke(null);
                    crashHandler.registerClient(mcInstance);
                    LOGGER.debug("The crash handler is attached to the Minecraft client.");
                }
            } else {
                LOGGER.debug("Could not find Minecraft client class for crash handler");
            }
        } catch (Exception e) {
            LOGGER.debug("Crash handler not available: {}", e.getMessage());
        }
    }
}
