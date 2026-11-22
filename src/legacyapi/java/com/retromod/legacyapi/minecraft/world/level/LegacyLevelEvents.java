/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.gameevent.GameEvent;

/** Level events and game events in their 1.20.1 forms: a player source, and game events before they were holders. */
public final class LegacyLevelEvents {

    private LegacyLevelEvents() {}

    /** {@code level.levelEvent(player, type, pos, data)}: any entity may be the source now. */
    public static void levelEvent(LevelAccessor level, Player player, int type, BlockPos pos, int data) {
        level.levelEvent(player, type, pos, data);
    }

    /** {@code level.gameEvent(entity, event, pos)}. */
    public static void gameEvent(LevelAccessor level, Entity entity, GameEvent event, BlockPos pos) {
        level.gameEvent(entity, BuiltInRegistries.GAME_EVENT.wrapAsHolder(event), pos);
    }

    /** {@code level.gameEvent(event, pos, context)}. */
    public static void gameEvent(LevelAccessor level, GameEvent event, BlockPos pos, GameEvent.Context context) {
        level.gameEvent(BuiltInRegistries.GAME_EVENT.wrapAsHolder(event), pos, context);
    }
}
