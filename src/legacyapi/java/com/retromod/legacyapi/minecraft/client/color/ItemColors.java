/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.color;

import java.util.function.ToIntBiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/** Minecraft's old item color table; every mod's copy reads and writes the one table the game shares. */
public class ItemColors {

    private static final ItemColors INSTANCE = new ItemColors();

    public ItemColors() {}

    /** {@code Minecraft.getItemColors()}, which Minecraft 1.21.4 removed. */
    public static ItemColors legacyOf(Minecraft minecraft) {
        return INSTANCE;
    }

    /** This copy's table, as the item color event hands it out. */
    public static ItemColors shared() {
        return INSTANCE;
    }

    /** Colors these items with {@code color}, drawn with the alpha it answers, as Minecraft 1.21's item renderer read it. */
    public void register(ItemColor color, ItemLike... items) {
        put(new Handler(color, false), items);
    }

    /** {@code register} for a mod built before 1.21, whose item renderer drew every tint opaque whatever alpha it answered. */
    public void registerOpaque(ItemColor color, ItemLike... items) {
        put(new Handler(color, true), items);
    }

    // A later registration for an item replaces an earlier one, as before.
    private static void put(ToIntBiFunction<Object, Integer> call, ItemLike... items) {
        for (ItemLike like : items) {
            Item item = like == null ? null : like.asItem();
            if (item != null) LegacyItemColorTable.table().put(item, call);
        }
    }

    /** The registered color for one tint index of the stack, or -1 when no mod colors its item. */
    public int getColor(ItemStack stack, int tintIndex) {
        return colorOf(stack, tintIndex);
    }

    static int colorOf(ItemStack stack, int tintIndex) {
        ToIntBiFunction<Object, Integer> color = handlerOf(stack);
        return color == null ? -1 : color.applyAsInt(stack, tintIndex);
    }

    /** The registered color of the stack's item, from any mod's copy, or null. */
    static ToIntBiFunction<Object, Integer> handlerOf(ItemStack stack) {
        return stack == null ? null : LegacyItemColorTable.table().get(stack.getItem());
    }

    /** An old item color as a JDK function another copy can call, answering the color drawn; its name is the handler's class. */
    private record Handler(ItemColor color, boolean opaque) implements ToIntBiFunction<Object, Integer> {
        @Override
        public int applyAsInt(Object stack, Integer index) {
            // Each copy has its own ItemColor type, so the table keeps this JDK function over the handler.
            int value = color.getColor((ItemStack) stack, index);
            // 26.x draws a tint with no alpha invisible; -1, no tint, stays -1.
            return opaque ? value | 0xFF000000 : value;
        }

        @Override
        public String toString() {
            return color.getClass().getName();
        }
    }
}
