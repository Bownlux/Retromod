/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;

/** Single vanilla members a 1.21.1 mod calls that 1.21.2 to 1.21.11 removed, which Retromod rebuilds on those hosts. */
final class VanillaGapsCase {

    private VanillaGapsCase() {}

    /** {@code Direction.fromDelta}, which 1.21.2 removed. */
    static boolean fromDelta() {
        return Direction.fromDelta(0, 1, 0) == Direction.UP && Direction.fromDelta(2, 0, 0) == null;
    }

    /** A projectile's {@code mayInteract(level, pos)} and an entity's {@code sendSystemMessage}, on a running server. */
    static String server(ServerLevel level) {
        // 1.21.2 made mayInteract take the server level; a new arrow with no owner may interact.
        Projectile arrow = new Arrow(EntityType.ARROW, level);
        if (!arrow.mayInteract(level, BlockPos.ZERO)) return "fail: an ownerless arrow may not interact";
        // 1.21.5 left sendSystemMessage to players; any other entity ignored the message before too.
        Entity zombie = new Zombie(EntityType.ZOMBIE, level);
        zombie.sendSystemMessage(Component.literal("retromod"));
        return "success";
    }
}
