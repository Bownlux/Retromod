/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import java.util.List;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/** {@code options.graphicsMode()} on 26.2 and newer, whose option constructor takes a value listener. */
public final class LegacyGraphicsModeOption262 {

    private LegacyGraphicsModeOption262() {}

    /** An option holding the closest old mode now; setting it picks that preset. */
    public static OptionInstance<GraphicsPreset> graphicsMode(Options options) {
        return LegacyGraphicsPresets.option(options, LegacyGraphicsModeOption262::create);
    }

    private static OptionInstance<GraphicsPreset> create(Options options) {
        OptionInstance.ValueUpdateListener<GraphicsPreset> onChange = mode -> LegacyGraphicsPresets.apply(options, mode);
        return new OptionInstance<>("options.graphics", OptionInstance.noTooltip(),
                (caption, mode) -> Component.translatable(mode.getKey()),
                new OptionInstance.Enum<>(List.of(GraphicsPreset.FAST, GraphicsPreset.FANCY, GraphicsPreset.FABULOUS), GraphicsPreset.CODEC),
                LegacyGraphicsPresets.closest(options), onChange);
    }
}
