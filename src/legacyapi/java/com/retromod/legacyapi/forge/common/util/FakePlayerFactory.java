/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.common.util;

import com.mojang.authlib.GameProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

/** Forge 1.20.1's {@code FakePlayerFactory}: one fake player per level and profile, until the level unloads or the server stops. */
public final class FakePlayerFactory {

    // Forge's own fake player keeps its old id and name, so data saved with them still matches.
    private static final GameProfile MINECRAFT =
            new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[Minecraft]");

    private static final Map<Key, FakePlayer> PLAYERS = new HashMap<>();
    private static boolean listening;

    private FakePlayerFactory() {}

    public static FakePlayer getMinecraft(ServerLevel level) {
        return get(level, MINECRAFT);
    }

    public static synchronized FakePlayer get(ServerLevel level, GameProfile profile) {
        listenForCleanup();
        Key key = new Key(level, profile);
        FakePlayer cached = PLAYERS.get(key);
        if (cached != null) return cached;
        // Built outside a map callback, since a listener that runs while it is built may ask for one too.
        FakePlayer built = new FakePlayer(level, profile);
        FakePlayer first = PLAYERS.putIfAbsent(key, built);
        return first != null ? first : built;
    }

    public static synchronized void unloadLevel(ServerLevel level) {
        PLAYERS.entrySet().removeIf(entry -> entry.getKey().level() == level || entry.getValue().level() == level);
    }

    private static synchronized void clear() {
        PLAYERS.clear();
    }

    // Subscribed on first use, once the game's buses exist, and once for each mod's copy of this class.
    private static void listenForCleanup() {
        if (listening) return;
        listening = true;
        LevelEvent.Unload.BUS.addListener(FakePlayerFactory::onLevelUnload);
        ServerStoppedEvent.BUS.addListener(event -> clear());
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) unloadLevel(level);
    }

    private record Key(ServerLevel level, GameProfile profile) {}
}
