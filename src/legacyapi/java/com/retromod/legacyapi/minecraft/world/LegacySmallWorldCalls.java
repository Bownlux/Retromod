/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world;

import com.mojang.authlib.GameProfile;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/** Small world and entity calls the host removed, each rebuilt on the member that replaced it. */
public final class LegacySmallWorldCalls {

    private LegacySmallWorldCalls() {}

    /** {@code entity.spawnAtLocation(stack)}: drops only on the server, as the old call did, and answers null elsewhere. */
    public static ItemEntity spawnAtLocation(Entity entity, ItemStack stack) {
        return entity.level() instanceof ServerLevel level ? entity.spawnAtLocation(level, stack) : null;
    }

    /** {@code entity.spawnAtLocation(stack, offsetY)}: the same, raised by the offset. */
    public static ItemEntity spawnAtLocation(Entity entity, ItemStack stack, float offsetY) {
        return entity.level() instanceof ServerLevel level ? entity.spawnAtLocation(level, stack, offsetY) : null;
    }

    /** {@code entity.spawnAtLocation(item)}: one of the item. */
    public static ItemEntity spawnAtLocation(Entity entity, ItemLike item) {
        return entity.level() instanceof ServerLevel level ? entity.spawnAtLocation(level, item) : null;
    }

    /** {@code entity.spawnAtLocation(item, offsetY)}, whose offset was a whole number of blocks. */
    public static ItemEntity spawnAtLocation(Entity entity, ItemLike item, int offsetY) {
        return entity.level() instanceof ServerLevel level ? entity.spawnAtLocation(level, new ItemStack(item), offsetY) : null;
    }

    /** {@code entity.getArmorSlots()}: the four armor stacks, from feet to head, as the old list held them. */
    public static Iterable<ItemStack> getArmorSlots(LivingEntity entity) {
        List<ItemStack> stacks = new ArrayList<>();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST,
                EquipmentSlot.HEAD}) {
            stacks.add(entity.getItemBySlot(slot));
        }
        return stacks;
    }

    /** {@code entity.getAllSlots()}: every equipment slot's stack, in slot order. */
    public static Iterable<ItemStack> getAllSlots(LivingEntity entity) {
        List<ItemStack> stacks = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) stacks.add(entity.getItemBySlot(slot));
        return stacks;
    }

    /** {@code players.isOp(profile)}, which now takes the player's name and id. */
    public static boolean isOp(PlayerList players, GameProfile profile) {
        return players.isOp(new NameAndId(profile));
    }

    /** {@code biome.getTemperature(pos)}: the height-adjusted value is private now, so the base temperature stands in. */
    public static float getTemperature(Biome biome, BlockPos pos) {
        LegacyLog.warnOnce("legacy biome temperature " + LegacySmallWorldCalls.class.getName(), "An old mod read a biome's"
                + " temperature at a position. Minecraft no longer offers that, so it gets the biome's base temperature,"
                + " without the cooling at high altitude.");
        return biome.getBaseTemperature();
    }

    /** {@code entity.canBeCollidedWith()}: the check now names the entity asking, and no entity stands for any. */
    public static boolean canBeCollidedWith(Entity entity) {
        return entity.canBeCollidedWith(null);
    }

    /** {@code EntityType.updateCustomEntityTag(level, player, entity, data)}: the data is now typed by the entity's type. */
    public static void updateCustomEntityTag(Level level, Player player, Entity entity, CustomData data) {
        // An empty or missing tag changed nothing before, so nothing is built for it.
        if (data == null || data.isEmpty()) return;
        EntityType.updateCustomEntityTag(level, player, entity, TypedEntityData.<EntityType<?>>of(entity.getType(), data.copyTag()));
    }

    /** {@code stack.getDescriptionId()}: the key of the stack's own name, such as a potion's effect key, else the item's key. */
    public static String getDescriptionId(ItemStack stack) {
        // The stack's name is where the per-stack key lives now; a literal or custom name falls back to the item.
        if (stack.getItemName().getContents() instanceof TranslatableContents translatable) return translatable.getKey();
        return stack.getItem().getDescriptionId();
    }
}
