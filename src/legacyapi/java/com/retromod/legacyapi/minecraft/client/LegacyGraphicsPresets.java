/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;

/** Reads the old fast, fancy and fabulous graphics mode from the graphics preset that replaced it. */
public final class LegacyGraphicsPresets {

    // One old option per game options, so a mod that keeps it or compares it gets the same object back.
    private static final Map<Options, OptionInstance<GraphicsPreset>> OPTIONS = new WeakHashMap<>();

    private LegacyGraphicsPresets() {}

    /** The old option of these options, made once and brought up to the current preset on each call. */
    static OptionInstance<GraphicsPreset> option(Options options, Function<Options, OptionInstance<GraphicsPreset>> make) {
        OptionInstance<GraphicsPreset> option;
        synchronized (OPTIONS) {
            option = OPTIONS.computeIfAbsent(options, make);
        }
        // Setting the closest mode back is a no-op for the preset, since apply skips the mode it already reads as.
        GraphicsPreset now = closest(options);
        if (option.get() != now) option.set(now);
        return option;
    }

    /** {@code GraphicsStatus.getId()}: fast 0, fancy 1, fabulous 2, as the old modes were numbered. */
    public static int getId(GraphicsPreset mode) {
        return mode == GraphicsPreset.CUSTOM ? GraphicsPreset.FANCY.ordinal() : mode.ordinal();
    }

    /** {@code GraphicsStatus.byId(id)}: the mode of that number, wrapping around as the old lookup did. */
    public static GraphicsPreset byId(int id) {
        return new GraphicsPreset[] {GraphicsPreset.FAST, GraphicsPreset.FANCY, GraphicsPreset.FABULOUS}[Math.floorMod(id, 3)];
    }

    /** The old mode closest to the player's settings: a custom preset is fabulous with improved transparency, else fancy. */
    public static GraphicsPreset closest(Options options) {
        GraphicsPreset preset = options.graphicsPreset().get();
        if (preset != GraphicsPreset.CUSTOM) return preset;
        // Fabulous was the mode that sorted translucent layers, which improved transparency does now.
        return Boolean.TRUE.equals(options.improvedTransparency().get()) ? GraphicsPreset.FABULOUS : GraphicsPreset.FANCY;
    }

    /** An old set of the mode picks the preset of the same name, as the video settings screen does. */
    public static void apply(Options options, GraphicsPreset mode) {
        if (mode != null && mode != GraphicsPreset.CUSTOM && mode != closest(options)) options.graphicsPreset().set(mode);
    }
}
