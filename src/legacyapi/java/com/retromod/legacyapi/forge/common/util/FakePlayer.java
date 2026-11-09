/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.common.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;

/** Forge 1.20.1's {@code FakePlayer}, which Forge 26.2 removed: a server player with no client behind it. */
public class FakePlayer extends ServerPlayer {

    public FakePlayer(ServerLevel level, GameProfile profile) {
        super(level.getServer(), level, profile, ClientInformation.createDefault());
        // No client listens, so the connection drops whatever the game sends this player.
        this.connection = new FakePlayerConnection(level.getServer(), this);
    }

    // Messages, statistics, options, ticking, and death never reach anyone, as before.
    @Override
    public void sendSystemMessage(Component message, boolean overlay) {}

    @Override
    public void awardStat(Stat<?> stat, int amount) {}

    @Override
    public void updateOptions(ClientInformation information) {}

    @Override
    public void tick() {}

    @Override
    public void die(DamageSource source) {}

    // Nothing hurts a fake player, and it hurts no real player.
    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return true;
    }

    @Override
    public boolean canHarmPlayer(Player other) {
        return false;
    }
}
