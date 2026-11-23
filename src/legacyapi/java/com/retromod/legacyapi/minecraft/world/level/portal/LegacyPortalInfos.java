/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.portal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** The 1.20.1 {@code PortalInfo} and Fabric API's {@code FabricDimensions.teleport}, rebuilt on {@code TeleportTransition}. */
public final class LegacyPortalInfos {

    private LegacyPortalInfos() {}

    /**
     * {@code new PortalInfo(pos, speed, yRot, xRot)}: a transition with no level yet, because the old target named only
     * where to arrive. The teleport call below supplies the level.
     */
    public static TeleportTransition create(Vec3 pos, Vec3 speed, float yRot, float xRot) {
        return new TeleportTransition(null, pos, speed, yRot, xRot, TeleportTransition.DO_NOTHING);
    }

    /**
     * {@code FabricDimensions.teleport(entity, destination, target)}: moves the entity to {@code destination} at the target,
     * and returns the entity that arrived, which for anything but a player is a new copy.
     */
    @SuppressWarnings("unchecked")
    public static <E extends Entity> E teleport(E entity, ServerLevel destination, TeleportTransition target) {
        TeleportTransition transition;
        if (target == null) {
            // Without a target the old call placed the entity at the destination's spawn.
            net.minecraft.core.BlockPos spawn = destination.getRespawnData().pos();
            transition = new TeleportTransition(destination, new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5),
                    Vec3.ZERO, entity.getYRot(), entity.getXRot(), TeleportTransition.DO_NOTHING);
        } else {
            transition = new TeleportTransition(destination, target.position(), target.deltaMovement(), target.yRot(),
                    target.xRot(), target.postTeleportTransition());
        }
        Entity arrived = entity.teleport(transition);
        return (E) arrived;
    }
}
