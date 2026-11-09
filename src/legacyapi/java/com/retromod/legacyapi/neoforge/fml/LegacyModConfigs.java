/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.fml;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;

import java.util.Locale;

/** Gives a mod built before FancyModLoader 12.0.8 the config type names and file names it was written for. */
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
