/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A rebase does not ask whether the host still has the class, so where it is registered is the
 * only thing deciding which hosts it reaches.
 *
 * <p>Minecraft removed the tool hierarchy in two steps. {@code SwordItem}, {@code PickaxeItem},
 * {@code DiggerItem} and {@code TieredItem} were already gone by 26.1. {@code AxeItem},
 * {@code ShovelItem} and {@code HoeItem} survived 26.1 and 26.2 and went at 26.3, checked against
 * all three jars. Registering that second group from the bridge that applies from 26.1 up would
 * take a 26.1 or 26.2 mod off a class its host still has, which is a worse outcome than the one
 * being fixed.
 */
class RemovedToolBaseHostGateTest {

    /** Removed at 26.3, so only a 26.3 host may see these rebased. */
    private static final List<String> WENT_AT_26_3 = List.of(
            "net/minecraft/world/item/AxeItem",
            "net/minecraft/world/item/ShovelItem",
            "net/minecraft/world/item/HoeItem");

    /** Already absent on 26.1, so the shared bridge is the right place. */
    private static final List<String> GONE_BY_26_1 = List.of(
            "net/minecraft/world/item/SwordItem",
            "net/minecraft/world/item/PickaxeItem",
            "net/minecraft/world/item/DiggerItem",
            "net/minecraft/world/item/TieredItem",
            "net/minecraft/world/item/ArmorItem");

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the 26.1 bridge leaves the classes 26.1 and 26.2 still have")
    void bridgeSkipsWhatSurvivedInto26_2() {
        RemovedItemBaseBridge.register(transformer);
        Set<String> rebased = transformer.getSuperclassRebaseSources();

        for (String stillThere : WENT_AT_26_3) {
            assertFalse(rebased.contains(stillThere),
                    stillThere + " is still present on 26.1 and 26.2, and this bridge applies from "
                    + "26.1 up, so rebasing it here moves a mod off a class its host has");
        }
        for (String gone : GONE_BY_26_1) {
            assertTrue(rebased.contains(gone),
                    gone + " is absent from 26.1 onwards, so every host this bridge reaches needs "
                    + "the rebase");
        }
    }

    @Test
    @DisplayName("the 26.3 step rebases the three tool classes it removed")
    void the26_3StepCoversWhatItRemoved() {
        Mc26_2To26_3CoreMoves.register(transformer);
        Set<String> rebased = transformer.getSuperclassRebaseSources();

        for (String removed : WENT_AT_26_3) {
            assertTrue(rebased.contains(removed),
                    removed + " goes at 26.3, so a mod extending it cannot load there without a "
                    + "stand-in base");
        }
    }

    @Test
    @DisplayName("neither step claims a tool base the other already owns")
    void thetwoStepsDoNotOverlap() {
        RemovedItemBaseBridge.register(transformer);
        Set<String> fromBridge = Set.copyOf(transformer.getSuperclassRebaseSources());
        transformer.clearRedirectsForTesting();
        Mc26_2To26_3CoreMoves.register(transformer);
        Set<String> from26_3 = transformer.getSuperclassRebaseSources();

        for (String removed : WENT_AT_26_3) {
            assertFalse(fromBridge.contains(removed) && from26_3.contains(removed),
                    removed + " is registered twice; the later registration silently replaces the "
                    + "generated base of the earlier one");
        }
    }
}
