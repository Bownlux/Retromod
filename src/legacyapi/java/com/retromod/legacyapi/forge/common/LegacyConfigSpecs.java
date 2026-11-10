/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.common;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.retromod.shim.common.embedded.LegacyLog;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Forge's config spec calls that NeoForge changed: {@code setConfig} and reading a value before its config loads. */
public final class LegacyConfigSpecs {

    private static final String LOADED_CONFIG = "net.neoforged.fml.config.LoadedConfig";
    private static final String TRACKER = "net.neoforged.fml.config.ConfigTracker";

    private LegacyConfigSpecs() {}

    /** Hands the spec the mod's config, so its values read from it at once as they did on Forge. */
    public static void setConfig(ModConfigSpec spec, CommentedConfig config) {
        if (config == null) {
            spec.acceptConfig(null);
            return;
        }
        try {
            // NeoForge seals the loaded-config type to its own record, built here as FML builds it.
            Path file = config instanceof FileConfig opened ? opened.getNioPath() : null;
            spec.acceptConfig(loaded(config, file, owner(spec, file)));
        } catch (ReflectiveOperationException | RuntimeException failed) {
            LegacyLog.warnOnce("setConfig " + spec.getClass().getName(), "Retromod could not hand a mod's own config"
                    + " file to its config spec (" + failed + "); the spec reads its values once NeoForge loads it.");
        }
    }

    /** Forge's {@code ConfigValue.get}, which answered the default until its config loaded; NeoForge's throws then. */
    public static Object get(ModConfigSpec.ConfigValue<?> value) {
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return value.getDefault();
        }
    }

    private static IConfigSpec.ILoadedConfig loaded(CommentedConfig config, Path file, ModConfig owner)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(LOADED_CONFIG, false, IConfigSpec.class.getClassLoader());
        Constructor<?> create = type.getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        create.setAccessible(true);
        return (IConfigSpec.ILoadedConfig) create.newInstance(config, file, owner);
    }

    /** The config NeoForge tracks for this spec, or an untracked one of the mod constructing now. */
    @SuppressWarnings("unchecked")
    private static ModConfig owner(ModConfigSpec spec, Path file) throws ReflectiveOperationException {
        Class<?> trackerType = Class.forName(TRACKER, false, IConfigSpec.class.getClassLoader());
        Object tracker = trackerType.getField("INSTANCE").get(null);
        Field byMod = trackerType.getDeclaredField("configsByMod");
        byMod.setAccessible(true);
        for (List<ModConfig> configs : ((Map<String, List<ModConfig>>) byMod.get(tracker)).values()) {
            for (ModConfig config : configs) {
                if (config.getSpec() == spec) return config;
            }
        }
        // A spec the mod never registered still needs an owner, which saving and reload events go through.
        Constructor<ModConfig> create = ModConfig.class.getDeclaredConstructor(ModConfig.Type.class, IConfigSpec.class,
                net.neoforged.fml.ModContainer.class, String.class, ReentrantLock.class);
        create.setAccessible(true);
        return create.newInstance(ModConfig.Type.values()[0], spec,
                ModLoadingContext.get().getActiveContainer(), file == null ? "" : file.getFileName().toString(),
                new ReentrantLock());
    }
}
