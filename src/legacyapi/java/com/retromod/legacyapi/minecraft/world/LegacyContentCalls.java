/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.biome.Biome;

/** World and data calls content mods made, rebuilt on 1.21.11 and 26.x, which take a sea level, a recipe key or an item lookup. */
public final class LegacyContentCalls {

    // The overworld's sea level, which the old calls used without asking.
    static final int SEA_LEVEL = 63;

    private LegacyContentCalls() {}

    /** {@code biome.getPrecipitationAt(pos)}. */
    public static Biome.Precipitation getPrecipitationAt(Biome biome, BlockPos pos) {
        return biome.getPrecipitationAt(pos, SEA_LEVEL);
    }

    /** {@code biome.coldEnoughToSnow(pos)}. */
    public static boolean coldEnoughToSnow(Biome biome, BlockPos pos) {
        return biome.coldEnoughToSnow(pos, SEA_LEVEL);
    }

    /** {@code biome.warmEnoughToRain(pos)}. */
    public static boolean warmEnoughToRain(Biome biome, BlockPos pos) {
        return biome.warmEnoughToRain(pos, SEA_LEVEL);
    }

    /** {@code new RecipeHolder(id, recipe)}: the holder is keyed by a recipe registry key now. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static RecipeHolder recipeHolder(Identifier id, Recipe recipe) {
        ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, id);
        return new RecipeHolder(key, recipe);
    }

    /** {@code ShapedRecipeBuilder.shaped(category, result)}, which now reads items through a lookup. */
    public static ShapedRecipeBuilder shaped(RecipeCategory category, ItemLike result) {
        return ShapedRecipeBuilder.shaped(BuiltInRegistries.ITEM, category, result);
    }

    public static ShapedRecipeBuilder shaped(RecipeCategory category, ItemLike result, int count) {
        return ShapedRecipeBuilder.shaped(BuiltInRegistries.ITEM, category, result, count);
    }

    /** {@code ShapelessRecipeBuilder.shapeless(category, result)}. */
    public static ShapelessRecipeBuilder shapeless(RecipeCategory category, ItemLike result) {
        return ShapelessRecipeBuilder.shapeless(BuiltInRegistries.ITEM, category, result);
    }

    public static ShapelessRecipeBuilder shapeless(RecipeCategory category, ItemLike result, int count) {
        return ShapelessRecipeBuilder.shapeless(BuiltInRegistries.ITEM, category, result, count);
    }
}
