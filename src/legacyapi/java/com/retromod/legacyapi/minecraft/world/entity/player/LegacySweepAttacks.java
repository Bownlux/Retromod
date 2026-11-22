/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity.player;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/** {@code Player.sweepAttack()}, the sweep effect a player could show before it became private. */
public final class LegacySweepAttacks {

    private LegacySweepAttacks() {}

    /** Shows the sweep particle in front of the player, at half its height, as the old public method did. */
    public static void sweepAttack(Player player) {
        double yaw = Math.toRadians(player.getYRot());
        double dx = -Math.sin(yaw);
        double dz = Math.cos(yaw);
        // Particles are sent from the server; on the client the old call drew nothing either.
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, player.getX() + dx, player.getY() + player.getBbHeight() * 0.5,
                    player.getZ() + dz, 0, dx, 0.0, dz, 0.0);
        }
    }
}
