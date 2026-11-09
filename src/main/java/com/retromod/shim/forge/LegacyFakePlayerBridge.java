/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;

/** Rebuilds Forge's FakePlayer and FakePlayerFactory, which Forge 26.2 removed, for old mods that act as a player. */
public final class LegacyFakePlayerBridge {

    static final String FAKE_PLAYER = "net/minecraftforge/common/util/FakePlayer";
    static final String FACTORY = "net/minecraftforge/common/util/FakePlayerFactory";
    static final String REBUILT = RebuiltApiClasses.ROOT + "forge/common/util/FakePlayer";
    static final String REBUILT_FACTORY = RebuiltApiClasses.ROOT + "forge/common/util/FakePlayerFactory";
    static final String CHECKS = RebuiltApiClasses.ROOT + "forge/common/util/FakePlayers";

    private LegacyFakePlayerBridge() {}

    /** Registers the rebuilt classes where the Forge host lacks FakePlayer; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        if (!ForgeHostShape.lacks(FAKE_PLAYER)) return;
        RebuiltApiClasses.registerTree(t, REBUILT);
        RebuiltApiClasses.registerTree(t, REBUILT_FACTORY);
        RebuiltApiClasses.registerTree(t, CHECKS);
        t.registerClassRedirect(FAKE_PLAYER, REBUILT);
        t.registerClassRedirect(FACTORY, REBUILT_FACTORY);
        // Each mod embeds its own copy, so a check such as Create's on a Quark fake player goes by name.
        t.registerInstanceofHelper(REBUILT, CHECKS, "isFake");
    }
}
