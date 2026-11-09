/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ToolMaterials;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;

import java.util.ArrayList;
import java.util.List;

/** DataFixerUpper 6's getOrThrow, a field typed as the old tool material enum, and an override 26.1 made final. */
public final class Test62OldDataAndFinalShapes implements Test {

    /** Typed as the enum, as Farmer's Delight's skillet tier is. */
    static final ToolMaterials SKILLET_TIER = ToolMaterials.IRON;

    @Override
    public String description() {
        return "DataResult.getOrThrow(boolean, Consumer), a ToolMaterials field, and a recipe overriding showNotification";
    }

    @Override
    public TestResult run() {
        // 1.20.5 removed getOrThrow(boolean, Consumer); it returns the value or reports the error once and throws.
        List<String> reported = new ArrayList<>();
        Object value = Codec.INT.parse(JsonOps.INSTANCE, new JsonPrimitive(7)).getOrThrow(false, reported::add);
        if (!Integer.valueOf(7).equals(value)) return TestResult.fail("getOrThrow returned " + value);
        try {
            Codec.INT.parse(JsonOps.INSTANCE, new JsonPrimitive("seven")).getOrThrow(false, reported::add);
            return TestResult.fail("an error result did not throw");
        } catch (RuntimeException expected) {
            if (reported.size() != 1 || !reported.get(0).equals(expected.getMessage())) {
                return TestResult.fail("reported " + reported + " and threw " + expected.getMessage());
            }
        }
        // 26.x made the tool materials a record; a field typed as the old enum still holds iron.
        if (SKILLET_TIER.getDurability() != 250) return TestResult.fail("iron durability read as " + SKILLET_TIER.getDurability());
        // KubeJS keys tiers by ToolMaterials.values()[i].toString(), and looks them up by name.
        ToolMaterials iron = ToolMaterials.values()[2];
        if (!iron.toString().equals("IRON") || !iron.name().equals("IRON") || ToolMaterials.valueOf("IRON") != SKILLET_TIER) {
            return TestResult.fail("values() gave " + iron + " named " + iron.name());
        }
        // 26.1 made showNotification final; JEI's recipe overrides it, and the class must still load.
        try {
            Class.forName(Test62OldDataAndFinalShapes.class.getName() + "$OwnShapedRecipe", false,
                    Test62OldDataAndFinalShapes.class.getClassLoader());
        } catch (Throwable refused) {
            return TestResult.fail("the recipe class did not load: " + refused);
        }
        return TestResult.success();
    }

    /** Built like JEI's JeiShapedRecipe, which hides the recipe unlock toast. Loaded, never constructed. */
    static final class OwnShapedRecipe extends ShapedRecipe {
        OwnShapedRecipe(Identifier id, DefaultedList<Ingredient> ingredients, ItemStack result) {
            super(id, "", CraftingRecipeCategory.MISC, 1, 1, ingredients, result);
        }

        @Override
        public boolean showNotification() {
            return false;
        }
    }
}
