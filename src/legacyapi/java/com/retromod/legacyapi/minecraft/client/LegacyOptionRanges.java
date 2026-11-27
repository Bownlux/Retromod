/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import net.minecraft.client.OptionInstance;

import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/** {@code IntRangeBase.xmap(to, from)}, which 1.21.11 gave a discrete flag. */
public final class LegacyOptionRanges {

    private LegacyOptionRanges() {}

    /** A mapped whole-number range steps one value at a time, as every vanilla option passes it. */
    public static <R> OptionInstance.SliderableValueSet<R> xmap(OptionInstance.IntRangeBase range,
            IntFunction<? extends R> to, ToIntFunction<? super R> from) {
        return range.xmap(to, from, true);
    }
}
