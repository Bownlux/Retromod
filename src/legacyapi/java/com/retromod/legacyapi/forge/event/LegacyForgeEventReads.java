/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.event;

import com.retromod.legacyapi.minecraft.world.item.enchantment.LegacyLootingLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.Result;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerSetSpawnEvent;
import net.minecraftforge.event.level.BlockEvent;

/** Forge 1.20.1's spawn, drop and break event members and destroy hook, answered from what Forge 26.2 and newer carry. */
public final class LegacyForgeEventReads {

    private LegacyForgeEventReads() {}

    /** {@code getNewSpawn()}: the new respawn position, or null when the spawn is being cleared. */
    public static BlockPos newSpawn(PlayerSetSpawnEvent event) {
        ServerPlayer.RespawnConfig config = event.getConfig();
        return config == null ? null : config.respawnData().pos();
    }

    /** {@code getSpawnLevel()}: the dimension of the new respawn position, or null when it is being cleared. */
    public static ResourceKey<Level> spawnLevel(PlayerSetSpawnEvent event) {
        ServerPlayer.RespawnConfig config = event.getConfig();
        return config == null ? null : config.respawnData().dimension();
    }

    /** {@code isForced()}: whether the respawn position holds without a bed or anchor. */
    public static boolean forced(PlayerSetSpawnEvent event) {
        ServerPlayer.RespawnConfig config = event.getConfig();
        return config != null && config.forced();
    }

    /** {@code LivingDropsEvent.getLootingLevel()}: the killer's looting level, which the event no longer carries. */
    public static int lootingLevel(LivingDropsEvent event) {
        return LegacyLootingLevel.of(event.getSource());
    }

    /** Forge 1.20.1's {@code new BreakEvent(level, pos, state, player)}: the event now takes a result, which starts as the default. */
    public static BlockEvent.BreakEvent breakEvent(Level level, BlockPos pos, BlockState state, Player player) {
        return new BlockEvent.BreakEvent(level, pos, state, player, Result.DEFAULT);
    }

    /** Forge 1.20.1's {@code ForgeHooks.canEntityDestroy(Level, ...)}: a client level answers no, since only the server keeps the game rule. */
    public static boolean canEntityDestroy(Level level, BlockPos pos, LivingEntity entity) {
        return level instanceof ServerLevel server && ForgeHooks.canEntityDestroy(server, pos, entity);
    }
}
