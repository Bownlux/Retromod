/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** GeckoLib 4.5 merged its core package into the main packages (#308). */
class GeckoLibCorePackageMovesTest {

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
    void mergedHostGetsTheCoreMoves() {
        Set<String> host = Set.of(GeckoLibCorePackageMoves.MERGED_CONTROLLER);
        assertTrue(GeckoLibCorePackageMoves.registerIfHostMerged(transformer, host::contains) > 30);
        assertEquals("software/bernie/geckolib/animation/AnimationController",
                transformer.getClassRedirects().get("software/bernie/geckolib/core/animation/AnimationController"));
        assertEquals("software/bernie/geckolib/animation/PlayState",
                transformer.getClassRedirects().get("software/bernie/geckolib/core/object/PlayState"));
        // Scorched Guns reads a controller's state to pick its gun animation.
        assertEquals("software/bernie/geckolib/animation/AnimationController$State",
                transformer.getClassRedirects().get("software/bernie/geckolib/core/animation/AnimationController$State"));
    }

    @Test
    void hostStillShippingCoreIsLeftAlone() {
        Set<String> host = Set.of(GeckoLibCorePackageMoves.MERGED_CONTROLLER, GeckoLibCorePackageMoves.CORE_CONTROLLER);
        assertEquals(0, GeckoLibCorePackageMoves.registerIfHostMerged(transformer, host::contains),
                "a 1.20.1 GeckoLib still has the core classes the mod names");
    }

    @Test
    void hostWithoutGeckoLib4IsLeftAlone() {
        assertEquals(0, GeckoLibCorePackageMoves.registerIfHostMerged(transformer, name -> false),
                "GeckoLib 5 hosts are handled by GeckoLib5ApiShim");
    }

    @Test
    void everyMoveLeavesTheCorePackage() {
        Map<String, String> moves = GeckoLibCorePackageMoves.classMoves();
        assertFalse(moves.isEmpty());
        moves.forEach((from, to) -> {
            assertTrue(from.startsWith("software/bernie/geckolib/core/"), from);
            assertFalse(to.contains("/core/"), from + " must move out of the core package, not to " + to);
        });
    }
}
