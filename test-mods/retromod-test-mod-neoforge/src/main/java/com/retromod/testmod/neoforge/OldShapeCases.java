/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;

/** Shapes from Farmer's Delight 1.21.1 and JEI 1.21.1 that later hosts replaced or made final. */
final class OldShapeCases {

    /** Typed as the enum, as Farmer's Delight's SKILLET_TIER is. */
    static final Tiers SKILLET_TIER = Tiers.IRON;

    private OldShapeCases() {}

    /** A field typed as Tiers holds iron, and the old enum call reads its durability. */
    static boolean tierField() {
        // KubeJS keys tiers by Tiers.values()[i].toString(), and looks them up by name.
        Tiers iron = Tiers.values()[2];
        return SKILLET_TIER.getUses() == 250 && iron.toString().equals("IRON") && iron.name().equals("IRON")
                && Tiers.valueOf("IRON") == SKILLET_TIER;
    }

    /** JEI's recipe overrides showNotification, which 26.1 made final; the class still loads. */
    static boolean finalOverrideLoads() throws ClassNotFoundException {
        return Class.forName(OldShapeCases.class.getName() + "$OwnShapedRecipe", false,
                OldShapeCases.class.getClassLoader()) != null;
    }

    /** Built like JEI's JeiShapedRecipe, which hides the recipe unlock toast. Loaded, never constructed. */
    static final class OwnShapedRecipe extends ShapedRecipe {
        OwnShapedRecipe(ShapedRecipePattern pattern, ItemStack result) {
            super("", CraftingBookCategory.MISC, pattern, result);
        }

        @Override
        public boolean showNotification() {
            return false;
        }
    }
}
