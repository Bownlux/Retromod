/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import java.util.stream.Stream;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;

/** Item members Minecraft reshaped after 1.21.1: cooldowns by stack, dye colors and tags as components, a static spawn egg type. */
public final class LegacyItemMembers {

    private LegacyItemMembers() {}

    /** {@code cooldowns.isOnCooldown(item)}: the item's default stack shares its cooldown group. */
    public static boolean isOnCooldown(Object cooldowns, Item item) {
        return ((ItemCooldowns) cooldowns).isOnCooldown(item.getDefaultInstance());
    }

    /** {@code cooldowns.addCooldown(item, ticks)}. */
    public static void addCooldown(Object cooldowns, Item item, int ticks) {
        ((ItemCooldowns) cooldowns).addCooldown(item.getDefaultInstance(), ticks);
    }

    /** {@code cooldowns.getCooldownPercent(item, partialTick)}. */
    public static float getCooldownPercent(Object cooldowns, Item item, float partialTick) {
        return ((ItemCooldowns) cooldowns).getCooldownPercent(item.getDefaultInstance(), partialTick);
    }

    /** {@code dyeItem.getDyeColor()}: the dye component every dye item now carries. */
    public static DyeColor getDyeColor(Object dyeItem) {
        return ((Item) dyeItem).components().get(DataComponents.DYE);
    }

    /** {@code stack.getTags()}: the item's tags. */
    public static Stream<TagKey<Item>> getTags(Object stack) {
        return ((ItemStack) stack).typeHolder().tags();
    }

    /** {@code spawnEgg.getType(stack)}, which 26.1 made static: the stack decides the type. */
    public static EntityType<?> spawnEggType(Object spawnEgg, ItemStack stack) {
        return SpawnEggItem.getType(stack);
    }
}
