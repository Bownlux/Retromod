/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import com.mojang.serialization.Codec;
import java.util.function.Consumer;
import net.minecraft.client.OptionInstance;

/** The option constructors that took a consumer, which 26.2 replaced with ones that take a value listener. */
public final class LegacyOptionInstances {

    private LegacyOptionInstances() {}

    /** {@code new OptionInstance(caption, tooltip, toString, values, initial, onChange)}. */
    public static <T> OptionInstance<T> create(String caption, OptionInstance.TooltipSupplier<T> tooltip,
            OptionInstance.CaptionBasedToString<T> toString, OptionInstance.ValueSet<T> values, T initial, Consumer<T> onChange) {
        OptionInstance.ValueUpdateListener<T> listener = onChange::accept;
        return new OptionInstance<>(caption, tooltip, toString, values, initial, listener);
    }

    /** The same with a codec. */
    public static <T> OptionInstance<T> create(String caption, OptionInstance.TooltipSupplier<T> tooltip,
            OptionInstance.CaptionBasedToString<T> toString, OptionInstance.ValueSet<T> values, Codec<T> codec, T initial,
            Consumer<T> onChange) {
        OptionInstance.ValueUpdateListener<T> listener = onChange::accept;
        return new OptionInstance<>(caption, tooltip, toString, values, codec, initial, listener);
    }
}
