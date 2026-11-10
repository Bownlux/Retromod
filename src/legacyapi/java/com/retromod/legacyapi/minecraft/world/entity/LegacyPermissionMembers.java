/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity;

import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.player.Player;

/** The operator-level check 1.21.11 replaced with permission sets, kept apart so other entity helpers load without them. */
public final class LegacyPermissionMembers {

    // The old check compared an operator level from 0 to 4, and an entity that was not a player held level 0.
    private static final int HIGHEST_LEVEL = 4;

    private LegacyPermissionMembers() {}

    /** {@code hasPermissions(level)}: whether the entity holds that operator level, as {@code getPermissionLevel() >= level} answered. */
    public static boolean hasPermissions(Object entity, int level) {
        if (level <= 0) return true;
        // PermissionLevel.byId clamps, so a level above the highest is answered here.
        if (level > HIGHEST_LEVEL || !(entity instanceof Player player)) return false;
        return player.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
    }
}
