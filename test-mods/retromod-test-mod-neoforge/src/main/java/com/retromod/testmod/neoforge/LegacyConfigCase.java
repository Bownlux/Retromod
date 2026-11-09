/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;

/** A COMMON and a SERVER config, which NeoForge 26.3 renamed to LOCAL and SYNCED, registered as Farmer's Delight does. */
final class LegacyConfigCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-NeoForge");
    private static final String PREFIX = "[Retromod-Test-NeoForge]";
    static final String MOD_ID = "retromod_test_mod_neoforge";

    private static final ModConfigSpec.BooleanValue COMMON_FLAG;
    private static final ModConfigSpec COMMON_SPEC;
    private static final ModConfigSpec.IntValue SERVER_VALUE;
    private static final ModConfigSpec SERVER_SPEC;

    static {
        ModConfigSpec.Builder common = new ModConfigSpec.Builder();
        COMMON_FLAG = common.comment("Edit this while the game runs to see the reload in the log.")
                .define("legacyCommonFlag", true);
        COMMON_SPEC = common.build();
        ModConfigSpec.Builder server = new ModConfigSpec.Builder();
        SERVER_VALUE = server.defineInRange("legacyServerValue", 7, 0, 100);
        SERVER_SPEC = server.build();
    }

    private LegacyConfigCase() {}

    /** Registers both configs from the constructor, and logs each load and reload. */
    static void register(ModContainer container, IEventBus modBus) {
        container.registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
        container.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
        modBus.addListener(ModConfigEvent.Loading.class, event -> logEvent("loaded", event));
        modBus.addListener(ModConfigEvent.Reloading.class, event -> logEvent("reloaded", event));
    }

    /** Both configs are registered under their 1.21.1 file names. */
    static boolean fileNamesKept() {
        return ModConfigs.getConfigFileNames(MOD_ID, ModConfig.Type.COMMON).equals(java.util.List.of(MOD_ID + "-common.toml"))
                && ModConfigs.getConfigFileNames(MOD_ID, ModConfig.Type.SERVER).equals(java.util.List.of(MOD_ID + "-server.toml"));
    }

    /** The type names config libraries build ids and file names from. */
    static boolean typeNamesKept() {
        return ModConfig.Type.COMMON.extension().equals("common")
                && ModConfig.Type.SERVER.name().equals("SERVER")
                && ModConfig.Type.valueOf("COMMON") == ModConfig.Type.COMMON;
    }

    /** Once a world loads, the server value reads and the common file is in config/ under its old name. */
    static String serverStarted() {
        int value = SERVER_VALUE.get();
        boolean commonFile = Files.isRegularFile(FMLPaths.CONFIGDIR.get().resolve(MOD_ID + "-common.toml"));
        boolean renamedFile = Files.exists(FMLPaths.CONFIGDIR.get().resolve(MOD_ID + "-local.toml"));
        if (value < 0 || !commonFile || renamedFile) {
            return "fail: server value " + value + ", common file " + commonFile + ", local file " + renamedFile;
        }
        return "success (server value " + value + ", common flag " + COMMON_FLAG.get() + ")";
    }

    private static void logEvent(String what, ModConfigEvent event) {
        ModConfig config = event.getConfig();
        if (!config.getModId().equals(MOD_ID)) return;
        Object value = config.getSpec() == COMMON_SPEC ? COMMON_FLAG.get() : SERVER_VALUE.get();
        LOG.info("{} {} config {} from {}: {}", PREFIX, config.getType().name(), what, config.getFileName(), value);
    }
}
