/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item.crafting;

import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

/** {@code Ingredient.of(TagKey)}, which 1.21.2 replaced with an ingredient of a holder set. */
public final class LegacyIngredients {

    private LegacyIngredients() {}

    /**
     * The items of {@code tag}. While mods register, the item registry's own named set is taken, which binds when tags load;
     * after the registry froze, the bound set is taken, and a tag it never had matches nothing, as an unknown tag did.
     */
    public static Ingredient of(TagKey<Item> tag) {
        HolderSet<Item> items;
        try {
            items = BuiltInRegistries.acquireBootstrapRegistrationLookup(BuiltInRegistries.ITEM).getOrThrow(tag);
        } catch (IllegalStateException frozen) {
            items = BuiltInRegistries.ITEM.get(tag)
                    .<HolderSet<Item>>map(named -> named)
                    .orElseGet(() -> HolderSet.emptyNamed(BuiltInRegistries.ITEM, tag));
        }
        return Ingredient.of(items);
    }
}
