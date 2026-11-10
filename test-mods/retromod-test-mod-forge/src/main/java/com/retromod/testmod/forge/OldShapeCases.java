/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.List;

/** Shapes from Balm, Farmer's Delight and JEI that later hosts removed or made final. */
final class OldShapeCases {

    /** Typed as the enum, as Farmer's Delight's SKILLET_TIER is. */
    static final Tiers SKILLET_TIER = Tiers.IRON;

    private OldShapeCases() {}

    /** DataFixerUpper 6's getOrThrow(boolean, Consumer): the value, or the message to the consumer and then a throw. */
    static boolean dataResultGetOrThrow() {
        List<String> reported = new ArrayList<>();
        Object value = Codec.INT.parse(JsonOps.INSTANCE, new JsonPrimitive(7)).getOrThrow(false, reported::add);
        try {
            Codec.INT.parse(JsonOps.INSTANCE, new JsonPrimitive("seven")).getOrThrow(false, reported::add);
            return false;
        } catch (RuntimeException expected) {
            return Integer.valueOf(7).equals(value) && reported.size() == 1
                    && reported.get(0).equals(expected.getMessage());
        }
    }

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
        OwnShapedRecipe(ResourceLocation id, NonNullList<Ingredient> ingredients, ItemStack result) {
            super(id, "", CraftingBookCategory.MISC, 1, 1, ingredients, result);
        }

        @Override
        public boolean showNotification() {
            return false;
        }
    }
}
