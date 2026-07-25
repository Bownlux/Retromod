/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Fabric API 26.1 networking rename moved the direction word to the FRONT and the channel to the
 * back: {@code playS2C -> clientboundPlay}, {@code configurationS2C -> clientboundConfiguration}, and
 * the C2S -> serverbound... mirror. The pattern-heuristic fallback used to do a naive
 * {@code replace("S2C","Clientbound")} and produce {@code playClientbound}, which 26.1 does not have,
 * so a mod that fell through the explicit shim table crashed {@code NoSuchMethodError} at init
 * (AppleSkin's {@code SyncHandler}, found in an in-game 26.2 launch). This proves the reconstruction.
 */
class PatternHeuristicsNetworkingTest {

    private final PatternHeuristics p = new PatternHeuristics();
    private static final String PTR_DESC = "()Lnet/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry;";
    private static final String PTR = "net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry";

    private String guess(String name) {
        var r = p.resolveMethod(PTR, name, PTR_DESC);
        return r == null ? null : r.newName();
    }

    @Test
    @DisplayName("<channel>S2C -> clientbound<Channel> and <channel>C2S -> serverbound<Channel>")
    void networkingRenameReconstructsTheRealName() {
        assertEquals("clientboundPlay", guess("playS2C"), "playS2C is clientboundPlay, not playClientbound");
        assertEquals("serverboundPlay", guess("playC2S"), "playC2S is serverboundPlay");
        assertEquals("clientboundConfiguration", guess("configurationS2C"));
        assertEquals("serverboundConfiguration", guess("configurationC2S"));
    }

    @Test
    @DisplayName("the rule only fires for networking owners, and not for a bare S2C/C2S name")
    void scopedToNetworkingOwners() {
        // non-networking owner: no guess
        assertNull(p.resolveMethod("net/minecraft/world/item/ItemStack", "playS2C", PTR_DESC),
                "a non-networking owner must not trigger the rename");
        // a bare "S2C"/"C2S" (empty channel) must not produce "clientbound"/"serverbound" with an
        // empty tail; the length guard declines it
        assertNull(guess("S2C"), "an empty channel is declined");
    }
}
