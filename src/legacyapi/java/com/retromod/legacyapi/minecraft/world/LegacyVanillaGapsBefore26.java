/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Members old mods call that hosts from 1.20.5 to 1.21.11 removed, built on calls those hosts have; each is kept only where its calls link. */
public final class LegacyVanillaGapsBefore26 {

    private LegacyVanillaGapsBefore26() {}

    /** {@code EnchantmentHelper.getEnchantmentId(tag)}: the id an old enchantment tag names, or null; 1.20.5 to 1.21.4 read tags this way. */
    public static ResourceLocation getEnchantmentId(CompoundTag tag) {
        Tag id = tag.get("id");
        return id == null ? null : ResourceLocation.tryParse(id.getAsString());
    }

    /** {@code EnchantmentHelper.getEnchantmentLevel(tag)}: the level an old enchantment tag holds, kept within 0 to 255. */
    public static int getEnchantmentLevel(CompoundTag tag) {
        return Math.max(0, Math.min(255, tag.getInt("lvl")));
    }

    /** {@code stack.getEnchantmentTags()}: the enchantments as the old list of id and level tags. */
    public static ListTag getEnchantmentTags(ItemStack stack) {
        ListTag list = new ListTag();
        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (var entry : enchantments.entrySet()) {
            CompoundTag tag = new CompoundTag();
            Optional<ResourceKey<Enchantment>> key = entry.getKey().unwrapKey();
            if (key.isPresent()) tag.putString("id", key.get().location().toString());
            tag.putShort("lvl", (short) entry.getIntValue());
            list.add(tag);
        }
        return list;
    }

    /** {@code EntityType.updateCustomEntityTag(level, player, entity, itemTag)}: the item tag's EntityTag, as the entity data an item carries now. */
    public static void updateCustomEntityTag(Level level, Player player, Entity entity, CompoundTag itemTag) {
        if (entity == null || itemTag == null || !(itemTag.get("EntityTag") instanceof CompoundTag data)) return;
        EntityType.updateCustomEntityTag(level, player, entity, CustomData.of(data));
    }

    /** {@code NbtUtils.readBlockState(tag)}: read against the block registry's lookup, which these hosts ask for. */
    public static BlockState readBlockState(CompoundTag tag) {
        return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag);
    }

    /** {@code block.getCloneItemStack(getter, pos, state)}: asked with the level reader 1.21 takes. */
    public static ItemStack getCloneItemStack(Block block, BlockGetter level, BlockPos pos, BlockState state) {
        if (level instanceof LevelReader reader) return block.getCloneItemStack(reader, pos, state);
        return new ItemStack(block);
    }

    /** {@code player.sendMessage(text, sender)} of 1.16 to 1.18, shown in chat as every player still can. */
    public static void sendMessage(Player player, Component text, java.util.UUID sender) {
        player.displayClientMessage(text, false);
    }

    /** {@code entity.sendSystemMessage(text)}: players read it in chat, as before; 1.21.11 left the call to the server player. */
    public static void sendSystemMessage(Entity entity, Component text) {
        if (entity instanceof Player player) player.displayClientMessage(text, false);
    }

    /** {@code contents.accountSimpleStack(stack)}: counted the way the item contents count it, from 1.21.2. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void accountSimpleStack(Object contents, ItemStack stack) {
        if (contents instanceof StackedItemContents items) {
            items.accountSimpleStack(stack);
        } else if (contents instanceof StackedContents raw && !stack.isEmpty()) {
            raw.account(stack.getItemHolder(), stack.getCount());
        }
    }
}
