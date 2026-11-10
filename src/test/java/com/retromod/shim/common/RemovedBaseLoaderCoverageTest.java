/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A removed base has to be rebased on every loader that can host the mod.
 *
 * <p>Forge once assembled its own list of the shared 26.1 adaptations instead of calling the
 * common {@code register()}, so it silently missed the removed-base bridges entirely. A Forge mod
 * with a custom tool, armor piece or music disc transformed correctly through the CLI, because
 * that path does call {@code register()}, and then failed at its {@code extends} when the game ran
 * it. Every loader shim now calls {@code register()}; this stays per loader so a shim that stops
 * calling it fails here.
 */
class RemovedBaseLoaderCoverageTest {

    /** One per bridge, chosen because mods really extend them. */
    private static final List<String> MUST_REBASE = List.of(
            "net/minecraft/world/item/RecordItem",
            "net/minecraft/world/item/SwordItem",
            "net/minecraft/world/item/ArmorItem",
            "net/minecraft/client/gui/screens/inventory/EffectRenderingInventoryScreen",
            "net/minecraft/core/dispenser/AbstractProjectileDispenseBehavior");

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

    private void assertCovers(String loader, Runnable register) {
        transformer.clearRedirectsForTesting();
        register.run();
        Set<String> rebased = transformer.getSuperclassRebaseSources();
        for (String base : MUST_REBASE) {
            assertTrue(rebased.contains(base),
                    loader + " does not rebase " + base + ", so a mod extending it loads on the "
                    + "other loaders and fails here. Check that this loader's 26.1 shim still "
                    + "calls Common_1_21_11_to_26_1_ClassMoves.register()");
        }
    }

    @Test
    @DisplayName("Forge rebases every removed base")
    void forgeCovers() {
        assertCovers("Forge", () -> new Forge_1_21_11_to_26_1().registerRedirects(transformer));
    }

    @Test
    @DisplayName("NeoForge rebases every removed base")
    void neoForgeCovers() {
        assertCovers("NeoForge", () -> new NeoForge_1_21_11_to_26_1().registerRedirects(transformer));
    }

    @Test
    @DisplayName("Fabric rebases every removed base")
    void fabricCovers() {
        assertCovers("Fabric", () -> new Fabric_1_21_11_to_26_1().registerRedirects(transformer));
    }
}
