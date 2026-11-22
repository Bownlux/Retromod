/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.server.level;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;

/** The boss bar constructor without an id, which 26.1 replaced with one that takes the id first. */
public final class LegacyBossEvents {

    private LegacyBossEvents() {}

    /** {@code new ServerBossEvent(name, color, overlay)}: a random id, as the old constructor made for itself. */
    public static ServerBossEvent create(Component name, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
        return new ServerBossEvent(UUID.randomUUID(), name, color, overlay);
    }
}
