/*
 * Retromod Test Library (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testlib.forge;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/** The library's own fake player and fake player check, typed as plain players so each jar keeps its own copy of the rebuilt class. */
public final class LibFakePlayers {

    private LibFakePlayers() {}

    /** A fake player from this library's copy of the factory. */
    public static Player fakePlayer(ServerLevel level) {
        return FakePlayerFactory.get(level, new GameProfile(new UUID(0x5265746fL, 1L), "retromod_test_lib"));
    }

    /** The library's own check, as Lootr's and Balm's are. */
    public static boolean isFakePlayer(Player player) {
        return player instanceof FakePlayer;
    }
}
