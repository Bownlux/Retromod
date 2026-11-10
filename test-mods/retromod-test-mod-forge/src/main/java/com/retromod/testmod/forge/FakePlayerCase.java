/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.mojang.authlib.GameProfile;
import com.retromod.testlib.forge.LibFakePlayers;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 1.20.1's FakePlayer and FakePlayerFactory, which Forge 26.2 removed. The library jar keeps
 * its own copy of the rebuilt class, so each jar's instanceof check must accept the other's fake
 * player. A fake player needs a running server, so the results are in the log rather than the summary.
 */
final class FakePlayerCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final GameProfile PROFILE = new GameProfile(new UUID(0x5265746fL, 2L), "retromod_test_mod");
    // Forge's own fake player has kept this id since 1.12, so saved owner data matches it.
    private static final UUID FORGE_FAKE_ID = UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77");

    private FakePlayerCase() {}

    static void register() {
        MinecraftForge.EVENT_BUS.addListener(FakePlayerCase::check);
    }

    private static void check(ServerStartedEvent event) {
        ServerLevel level = event.getServer().overworld();
        log("FakePlayerFactory.get returns one fake player per level and profile (server started)", () -> {
            Player mine = FakePlayerFactory.get(level, PROFILE);
            return mine == FakePlayerFactory.get(level, PROFILE) && mine instanceof FakePlayer;
        });
        log("the library's instanceof FakePlayer accepts this mod's fake player (server started)",
                () -> LibFakePlayers.isFakePlayer(FakePlayerFactory.get(level, PROFILE)));
        log("this mod's instanceof FakePlayer accepts the library's fake player (server started)",
                () -> LibFakePlayers.fakePlayer(level) instanceof FakePlayer);
        log("the library's instanceof FakePlayer answers false for no player (server started)",
                () -> !LibFakePlayers.isFakePlayer(null));
        log("FakePlayerFactory.getMinecraft keeps Forge's old id and drops its messages (server started)", () -> {
            FakePlayer minecraft = FakePlayerFactory.getMinecraft(level);
            minecraft.sendSystemMessage(Component.literal("Retromod test"));
            return minecraft.getUUID().equals(FORGE_FAKE_ID);
        });
    }

    private static void log(String description, Check check) {
        String result;
        try {
            result = check.run() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-Forge] {}: {}", description, result);
    }

    @FunctionalInterface
    private interface Check {
        boolean run() throws Throwable;
    }
}
