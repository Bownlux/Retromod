/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.event;

import com.retromod.legacyapi.minecraft.world.item.enchantment.LegacyLootingLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/** NeoForge 1.21.1 griefing hooks that took any level and the drops event's looting level, answered on hosts that changed them. */
public final class LegacyHookReads {

    private LegacyHookReads() {}

    /** {@code EventHooks.canEntityGrief(Level, Entity)}: a client level answers no, since only the server keeps the game rule. */
    public static boolean canEntityGrief(Level level, Entity entity) {
        return level instanceof ServerLevel server && EventHooks.canEntityGrief(server, entity);
    }

    /** {@code CommonHooks.canEntityDestroy(Level, BlockPos, LivingEntity)}, with the same answer on a client level. */
    public static boolean canEntityDestroy(Level level, BlockPos pos, LivingEntity entity) {
        return level instanceof ServerLevel server && CommonHooks.canEntityDestroy(server, pos, entity);
    }

    /** Forge 1.20.1's {@code LivingDropsEvent.getLootingLevel()}: the killer's looting level, which the event no longer carries. */
    public static int lootingLevel(LivingDropsEvent event) {
        return LegacyLootingLevel.of(event.getSource());
    }
}
