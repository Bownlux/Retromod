/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item.crafting;

import com.mojang.serialization.Codec;
import java.util.List;
import net.minecraft.world.item.crafting.Ingredient;

/** NeoForge 1.21.1's ingredient list codecs, which old recipe serializers read their inputs with, built on the host's ingredient codec. */
public final class LegacyIngredientCodecs {

    private static volatile Codec<List<Ingredient>> list;
    private static volatile Codec<List<Ingredient>> nonEmpty;

    private LegacyIngredientCodecs() {}

    /** Old {@code Ingredient.LIST_CODEC}: any number of ingredients. */
    public static Codec<List<Ingredient>> list() {
        if (list == null) list = Ingredient.CODEC.listOf();
        return list;
    }

    /** Old {@code Ingredient.LIST_CODEC_NONEMPTY}: at least one ingredient, as a recipe with no inputs was refused. */
    public static Codec<List<Ingredient>> listNonEmpty() {
        if (nonEmpty == null) nonEmpty = Ingredient.CODEC.listOf(1, Integer.MAX_VALUE);
        return nonEmpty;
    }
}
