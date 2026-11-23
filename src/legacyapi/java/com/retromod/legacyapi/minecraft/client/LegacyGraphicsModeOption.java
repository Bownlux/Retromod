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

/** {@code options.graphicsMode()} on 1.21.11 and 26.1, whose option constructor takes a consumer. */
public final class LegacyGraphicsModeOption {

    private LegacyGraphicsModeOption() {}

    /** An option holding the closest old mode now; setting it picks that preset. */
    public static OptionInstance<GraphicsPreset> graphicsMode(Options options) {
        return LegacyGraphicsPresets.option(options, LegacyGraphicsModeOption::create);
    }

    private static OptionInstance<GraphicsPreset> create(Options options) {
        java.util.function.Consumer<GraphicsPreset> onChange = mode -> LegacyGraphicsPresets.apply(options, mode);
        return new OptionInstance<>("options.graphics", OptionInstance.noTooltip(),
                (caption, mode) -> Component.translatable(mode.getKey()),
                new OptionInstance.Enum<>(List.of(GraphicsPreset.FAST, GraphicsPreset.FANCY, GraphicsPreset.FABULOUS), GraphicsPreset.CODEC),
                LegacyGraphicsPresets.closest(options), onChange);
    }
}
