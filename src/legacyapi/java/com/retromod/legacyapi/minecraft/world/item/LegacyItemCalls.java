/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Item, enchantment, container and advancement calls removed after 1.20.1 or 1.21.1, each answered by what replaced them. */
public final class LegacyItemCalls {

    private LegacyItemCalls() {}

    /** {@code BlockItem.updateState(state, property, value)}: the state with that value, or unchanged for a value it lacks. */
    public static BlockState updateState(BlockState state, Property<?> property, String value) {
        return withValue(state, property, value);
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        // The state's setValue returns the state type, which the host erases to Object.
        return property.getValue(value).map(parsed -> (BlockState) state.setValue(property, parsed)).orElse(state);
    }

    /** {@code item.canAttackBlock(state, level, pos, player)}: asked about the stack in the player's main hand, as mining uses. */
    public static boolean canAttackBlock(Item item, BlockState state, Level level, BlockPos pos, Player player) {
        return item.canDestroyBlock(player.getMainHandItem(), state, level, pos, player);
    }

    /** {@code stack.onCraftedBy(level, player, amount)}: the level came from the player from 1.21.5. */
    public static void onCraftedBy(ItemStack stack, Level level, Player player, int amount) {
        stack.onCraftedBy(player, amount);
    }

    /** {@code egg.getType(tag)}: the entity the tag's {@code EntityTag} names, else the egg's own entity. */
    public static EntityType<?> spawnEggType(SpawnEggItem egg, CompoundTag tag) {
        String id = tag == null ? null : tag.getCompound("EntityTag").flatMap(entity -> entity.getString("id")).orElse(null);
        Identifier key = id == null ? null : Identifier.tryParse(id);
        // The entity registry answers pigs for an unknown id, so only a known id counts.
        if (key != null && BuiltInRegistries.ENTITY_TYPE.containsKey(key)) return BuiltInRegistries.ENTITY_TYPE.getValue(key);
        return SpawnEggItem.getType(new ItemStack(egg));
    }

    /** {@code EnchantmentHelper.hasVanishingCurse(stack)}: whether an enchantment keeps the stack from dropping on death. */
    public static boolean hasVanishingCurse(ItemStack stack) {
        return EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP);
    }

    /** {@code EnchantmentHelper.hasBindingCurse(stack)}: whether an enchantment keeps the stack from being taken off. */
    public static boolean hasBindingCurse(ItemStack stack) {
        return EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }

    /** {@code customData.contains(key)}: whether the stored tag has the key, read from a copy. */
    public static boolean contains(CustomData data, String key) {
        return data.copyTag().contains(key);
    }

    /** {@code bundle.items()}: the bundle holds stack templates now, so the old view gets a stack built from each. */
    public static Iterable<ItemStack> items(BundleContents bundle) {
        return bundle.items().stream().map(ItemStackTemplate::create).toList();
    }

    /** {@code advancement.getDisplay()}: the display, or null for an advancement shown nowhere, as the old field held it. */
    public static DisplayInfo getDisplay(Advancement advancement) {
        return advancement.display().orElse(null);
    }

    /** {@code display.getIcon()}: an advancement icon is a stack template now, so each call builds a new stack from it. */
    public static ItemStack getIcon(DisplayInfo display) {
        return display.icon().create();
    }
}
