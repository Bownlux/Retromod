/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import java.util.function.Consumer;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;

/** The old {@code OptionInstance.createBoolean} that took a Consumer, and the touchscreen option, on 26.2 and newer. */
public final class LegacyBooleanOptions {

    private LegacyBooleanOptions() {}

    /** {@code OptionInstance.createBoolean(caption, initial, consumer)}, whose listener became its own interface in 26.2. */
    public static OptionInstance<Boolean> createBoolean(String caption, boolean initial, Consumer<Boolean> onChange) {
        return OptionInstance.createBoolean(caption, initial, onChange::accept);
    }

    /** {@code options.touchscreen()}: 26.2 removed touchscreen mode, so this reads off each time it is asked for and is never saved. */
    public static OptionInstance<Boolean> touchscreen(Options options) {
        // A mod that set it on would otherwise see its own value, though nothing in the game reads it.
        Touchscreen.OFF.set(false);
        return Touchscreen.OFF;
    }

    // Made on first use, after the game's options exist.
    private static final class Touchscreen {
        static final OptionInstance<Boolean> OFF = OptionInstance.createBoolean("options.touchscreen", false, value -> { });
    }
}
