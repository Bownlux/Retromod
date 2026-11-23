/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.gamerules;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.notifications.EmptyNotificationService;
import net.minecraft.world.level.gamerules.GameRule;

/** Hears one server's game rule changes, which the host reports for /gamerule and every other write, and runs the old callbacks. */
final class LegacyGameRuleNotifications extends EmptyNotificationService {

    private final MinecraftServer server;

    LegacyGameRuleNotifications(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public <T> void onGameRuleChanged(GameRule<T> rule, T value) {
        LegacyGameRules.changed(server, rule);
    }
}
