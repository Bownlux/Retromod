/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wiring and loader-safety coverage for {@link RetromodNeoForge#registerPolyfills}.
 *
 * <p>The NeoForge runtime transform used to load no
 * {@link com.retromod.polyfill.PolyfillProvider}s (only Fabric did). This pins the wiring:
 * <ul>
 *   <li>the Forge to NeoForge migration redirects (representative {@code Dist}
 *       relocation) are registered;</li>
 *   <li>the {@code neoforge}-category transfer polyfills, owned by the host-gated
 *       {@code NeoForge_1_21_8_to_1_21_9} shim, are not registered;</li>
 *   <li>removed-class polyfills self-gate on the host version;</li>
 *   <li>{@code polyfills_enabled=false} registers nothing.</li>
 * </ul>
 */
class RetromodNeoForgePolyfillTest {

    private static final String FORGE_DIST = "net/minecraftforge/api/distmarker/Dist";
    private static final String NEOFORGE_DIST = "net/neoforged/api/distmarker/Dist";
    private static final String IITEMHANDLER = "net/neoforged/neoforge/items/IItemHandler";
    private static final String ITEMSTACKHANDLER = "net/neoforged/neoforge/items/ItemStackHandler";
    private static final String DIRECTION_PROPERTY =
            "net/minecraft/world/level/block/state/properties/DirectionProperty";
    private static final String ENUM_PROPERTY =
            "net/minecraft/world/level/block/state/properties/EnumProperty";

    private final RetromodTransformer t = RetromodTransformer.getInstance();
    private String savedVersion;

    @BeforeEach
    void setUp() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        t.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedVersion;
        t.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("NeoForge entry registers the Forge -> NeoForge Dist redirect (representative)")
    void registersForgeToNeoForgeDistRedirect() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodNeoForge.registerPolyfills(t, true);

        assertEquals(NEOFORGE_DIST, t.getClassRedirects().get(FORGE_DIST),
                "NeoForge entry must register the Forge -> NeoForge Dist relocation");
    }

    @Test
    @DisplayName("neoforge-category transfer polyfills are NOT registered (the shim owns them)")
    void doesNotRegisterConflictingTransferPolyfills() {
        // Leave the transfer transition to the host-gated NeoForge_1_21_8_to_1_21_9 shim (#9):
        // the polyfill's class redirect conflicts with the shim's member redirects, and
        // un-gated it would NoClassDefFoundError on a pre-1.21.9 host.
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodNeoForge.registerPolyfills(t, true);

        assertFalse(t.getClassRedirects().containsKey(IITEMHANDLER),
                "IItemHandler -> ResourceHandler must NOT be registered on the NeoForge runtime");
        assertFalse(t.getClassRedirects().containsKey(ITEMSTACKHANDLER),
                "ItemStackHandler must not be hijacked on the NeoForge runtime");
    }

    @Test
    @DisplayName("Removed-class polyfills are host-gated: off pre-26.1, on at 26.1+")
    void removedClassPolyfillsAreHostGated() {
        // Pre-26.1 host: DirectionProperty still exists, so don't redirect it.
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodNeoForge.registerPolyfills(t, true);
        assertFalse(t.getClassRedirects().containsKey(DIRECTION_PROPERTY),
                "DirectionProperty exists pre-26.1; redirecting it would hijack live code");

        // 26.1 host: removed, so it redirects to its surviving superclass.
        t.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodNeoForge.registerPolyfills(t, true);
        assertEquals(ENUM_PROPERTY, t.getClassRedirects().get(DIRECTION_PROPERTY),
                "DirectionProperty must bridge to EnumProperty on 26.1+");
    }

    @Test
    @DisplayName("Old-name redirects are additive; live Mojang classes are never redirect sources")
    void onlyAbsentNamesAreRedirectSources() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodNeoForge.registerPolyfills(t, true);

        // Old MCP names (BlockPolyfill) still register, but key on names absent from any
        // modern NeoForge mod, so the redirect is dead weight rather than a misfire.
        assertEquals("net/minecraft/world/level/block/AnvilBlock",
                t.getClassRedirects().get("net/minecraft/block/BlockAnvil"),
                "old MCP-named redirects still register (additive); they key on absent names");

        // On a Mojang-named runtime a live, never-removed core class must never be a
        // redirect source, so it can't be hijacked.
        assertFalse(t.getClassRedirects().containsKey("net/minecraft/world/item/ItemStack"),
                "a live core Mojang class must never be a class-redirect source");
        assertFalse(t.getClassRedirects().containsKey("net/minecraft/resources/ResourceLocation"),
                "a live core Mojang class must never be a class-redirect source");
    }

    @Test
    @DisplayName("polyfills_enabled=false registers nothing")
    void disabledFlagRegistersNothing() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodNeoForge.registerPolyfills(t, false);

        assertFalse(t.getClassRedirects().containsKey(FORGE_DIST),
                "with polyfills disabled, not even the Dist redirect should register");
        assertTrue(t.getClassRedirects().isEmpty(),
                "polyfills disabled => no class redirects registered");
    }
}
