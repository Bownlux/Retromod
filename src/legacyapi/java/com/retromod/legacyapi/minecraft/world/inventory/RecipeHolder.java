/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.inventory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The 1.20.1 block entity interface for the recipe it last made, which 1.20.2 renamed to
 * {@code RecipeCraftingHolder} and retyped to recipe holders. A 1.20.1 block entity implements
 * this one, so its own calls and overrides keep their old shape.
 */
public interface RecipeHolder {

    void setRecipeUsed(Recipe<?> recipe);

    Recipe<?> getRecipeUsed();

    /**
     * Forgets the recipe after a result is taken. The recipe book unlock and crafting trigger that
     * 1.20.1 also ran need the recipe's holder, which a bare recipe no longer names, so a block
     * entity that wants them overrides this method, as Farmer's Delight's cooking pot does.
     */
    default void awardUsedRecipes(Player player, List<ItemStack> items) {
        if (getRecipeUsed() != null) setRecipeUsed(null);
    }

    /** Records the recipe and allows the craft; the limited crafting rule is not checked. */
    default boolean setRecipeUsed(Level level, ServerPlayer player, Recipe<?> recipe) {
        setRecipeUsed(recipe);
        return true;
    }
}
