/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.fml;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Gives old mods on NeoForge the config type names, file names, construction and tracker reads they were written for. */
public final class LegacyModConfigs {

    private LegacyModConfigs() {}

    /** {@code container.registerConfig(type, spec)}, under the file name the mod used before the rename. */
    public static void registerConfig(ModContainer container, ModConfig.Type type, IConfigSpec spec) {
        // An existing modid-common.toml keeps its values, and the mod finds the file where it always was.
        container.registerConfig(type, spec, fileName(container.getModId(), type));
    }

    /** {@code tracker.registerConfig(type, spec, container)}, under the old file name too. */
    public static ModConfig registerConfig(ConfigTracker tracker, ModConfig.Type type, IConfigSpec spec,
            ModContainer container) {
        return tracker.registerConfig(type, spec, container, fileName(container.getModId(), type));
    }

    /** Forge 1.20.1's {@code new ModConfig(type, spec, container, fileName)}: the tracker builds and tracks it, as FML does now. */
    public static ModConfig create(ModConfig.Type type, IConfigSpec spec, ModContainer container, String fileName) {
        return ConfigTracker.INSTANCE.registerConfig(type, spec, container, fileName);
    }

    /** Forge 1.20.1's {@code new ModConfig(type, spec, container)}, under the modid-type.toml name Forge gave it. */
    public static ModConfig create(ModConfig.Type type, IConfigSpec spec, ModContainer container) {
        return create(type, spec, container, fileName(container.getModId(), type));
    }

    /** Forge 1.20.1's {@code container.addConfig(config)}: a config built above is already tracked, so nothing is left to do. */
    public static void addConfig(ModContainer container, ModConfig config) {
    }

    /** Forge 1.20.1's {@code ConfigTracker.INSTANCE.fileMap()}: a copy of the configs by file name, which NeoForge keeps internal. */
    public static ConcurrentHashMap<String, ModConfig> fileMap(ConfigTracker tracker) {
        return new ConcurrentHashMap<>(ModConfigs.getFileMap());
    }

    /** Forge 1.20.1's {@code ConfigTracker.INSTANCE.configSets()}: the configs of each type. */
    public static Map<ModConfig.Type, Set<ModConfig>> configSets(ConfigTracker tracker) {
        Map<ModConfig.Type, Set<ModConfig>> sets = new EnumMap<>(ModConfig.Type.class);
        for (ModConfig.Type type : ModConfig.Type.values()) sets.put(type, ModConfigs.getConfigSet(type));
        return sets;
    }

    /** {@code type.extension()}, which named the renamed types common and server. */
    public static String extension(ModConfig.Type type) {
        return name(type).toLowerCase(Locale.ROOT);
    }

    /** {@code type.name()}, which was COMMON and SERVER for the renamed types. */
    public static String name(ModConfig.Type type) {
        // Through Enum, so this never reaches the redirect that sends a mod's own call here.
        String current = ((Enum<?>) type).name();
        return switch (current) {
            case "LOCAL" -> "COMMON";
            case "SYNCED" -> "SERVER";
            default -> current;
        };
    }

    /** {@code ModConfig.Type.valueOf(name)}, which also takes the old COMMON and SERVER. */
    public static ModConfig.Type valueOf(String name) {
        String current = switch (name) {
            case "COMMON" -> "LOCAL";
            case "SERVER" -> "SYNCED";
            default -> name;
        };
        return Enum.valueOf(ModConfig.Type.class, current);
    }

    /** The name FancyModLoader gave a config with no name of its own: modid-type.toml. */
    static String fileName(String modId, ModConfig.Type type) {
        return String.format(Locale.ROOT, "%s-%s.toml", modId, extension(type));
    }
}
