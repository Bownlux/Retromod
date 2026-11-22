/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** {@code Entity.createCommandSourceStack()}, which now takes the server level the source runs in. */
public final class LegacyEntityCommandSources {

    private LegacyEntityCommandSources() {}

    /** A command source for the entity, built on the level it is in. */
    public static CommandSourceStack createCommandSourceStack(Entity entity) {
        // A player keeps its own source, which carries its permissions.
        if (entity instanceof ServerPlayer player) return player.createCommandSourceStack();
        if (entity.level() instanceof ServerLevel level) return entity.createCommandSourceStackForNameResolution(level);
        throw new IllegalStateException("An old mod asked for a command source of " + entity
                + " on the client. Minecraft builds command sources only on the server now, so run the call there.");
    }
}
