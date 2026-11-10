/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity.monster.piglin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Player;

/** The {@code PiglinAi} member that 1.21.2 gave a level parameter, as a container block opened by a player calls it. */
public final class PiglinAiMembers {

    private PiglinAiMembers() {}

    /** {@code PiglinAi.angerNearbyPiglins(player, onlyIfSeen)}: piglins live on the server, so a client call does nothing. */
    public static void angerNearbyPiglins(Player player, boolean onlyIfSeen) {
        if (player.level() instanceof ServerLevel level) PiglinAi.angerNearbyPiglins(level, player, onlyIfSeen);
    }
}
