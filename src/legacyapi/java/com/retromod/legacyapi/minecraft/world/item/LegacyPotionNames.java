/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.world.item.alchemy.Potion;

/** {@code Potion.getName(potion, prefix)}, the translation key builder for potion items that 1.21.11 no longer has. */
public final class LegacyPotionNames {

    private LegacyPotionNames() {}

    /** The prefix followed by the potion's base name, or by {@code empty} when there is no potion, as before. */
    public static String getName(Optional<Holder<Potion>> potion, String prefix) {
        return prefix + potion.map(holder -> holder.value().name()).orElse("empty");
    }
}
