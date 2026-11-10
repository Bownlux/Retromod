/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/** An entity's death drops from a table of its own choosing, for 1.21.2 to 1.21.8, which have no drop that takes the table. */
public final class LegacyEntityLootDrops {

    private LegacyEntityLootDrops() {}

    /** Drops {@code table}'s loot where the entity died, with the context those hosts give an entity's own table. */
    public static void drop(LivingEntity entity, ServerLevel level, DamageSource source, boolean hitByPlayer,
            ResourceKey<LootTable> table, Player lastHurtByPlayer) {
        LootTable loot = level.getServer().reloadableRegistries().getLootTable(table);
        // The dying entity, where it was, and what killed it, directly and through a projectile.
        LootParams.Builder context = new LootParams.Builder(level)
                .withParameter(LootContextParams.THIS_ENTITY, entity)
                .withParameter(LootContextParams.ORIGIN, entity.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, source)
                .withOptionalParameter(LootContextParams.ATTACKING_ENTITY, source.getEntity())
                .withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, source.getDirectEntity());
        // A player's kill adds the player and their luck, which player-only drops and looting read.
        if (hitByPlayer && lastHurtByPlayer != null) {
            context = context.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, lastHurtByPlayer).withLuck(lastHurtByPlayer.getLuck());
        }
        loot.getRandomItems(context.create(LootContextParamSets.ENTITY), entity.getLootTableSeed(),
                stack -> entity.spawnAtLocation(level, stack));
    }
}
