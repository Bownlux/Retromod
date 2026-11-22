/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import java.util.function.Consumer;
import net.minecraft.client.OptionInstance;

/** The old {@code OptionInstance.createBoolean} that took a Consumer, on 26.2 and newer. */
public final class LegacyBooleanOptions {

    private LegacyBooleanOptions() {}

    /** {@code OptionInstance.createBoolean(caption, initial, consumer)}, whose listener became its own interface in 26.2. */
    public static OptionInstance<Boolean> createBoolean(String caption, boolean initial, Consumer<Boolean> onChange) {
        return OptionInstance.createBoolean(caption, initial, onChange::accept);
    }
}
