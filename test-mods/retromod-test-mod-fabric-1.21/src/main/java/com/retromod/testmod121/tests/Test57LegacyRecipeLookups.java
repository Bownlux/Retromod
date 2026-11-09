/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121.tests;

import com.retromod.testmod121.Test;
import com.retromod.testmod121.TestResult;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/** The 1.21.1 recipe lookups by type, which 1.21.2 moved into the recipe map. */
public final class Test57LegacyRecipeLookups implements Test {

    private final MinecraftServer server;

    public Test57LegacyRecipeLookups(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String description() {
        return "1.21.1 recipe lookups list the smelting recipes and find the one for raw iron";
    }

    @Override
    public TestResult run() {
        RecipeManager recipes = server.getRecipeManager();
        // getAllRecipesFor, which recipe viewers call to list a whole type.
        List<RecipeEntry<SmeltingRecipe>> smelting = recipes.listAllOfType(RecipeType.SMELTING);
        if (smelting.isEmpty()) return TestResult.fail("no smelting recipes were listed");

        // getRecipesFor, which machines call with what is in their input slot.
        SingleStackRecipeInput rawIron = new SingleStackRecipeInput(new ItemStack(Items.RAW_IRON));
        List<RecipeEntry<SmeltingRecipe>> matches = recipes.getAllMatches(RecipeType.SMELTING, rawIron, server.getOverworld());
        if (matches.isEmpty()) return TestResult.fail("raw iron matched none of " + smelting.size() + " smelting recipes");
        if (!smelting.containsAll(matches)) return TestResult.fail("a match is missing from the listed recipes: " + matches);
        return TestResult.success();
    }
}
