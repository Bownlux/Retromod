/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RetromodPreLaunch#isUnobfuscatedTarget(String)}: the gate
 * that decides whether the intermediary→Mojang remap (and 26.1 class moves) are
 * applied.
 *
 * <p>Regression coverage for bugs #21 and #29: applying the 26.1 remap on a
 * pre-26.1 host rewrites a Fabric mod's working intermediary references into
 * Mojang names that don't exist at the pre-26.1 runtime (Identifier,
 * ParticleType, BuiltInRegistries, Component, …) → ClassNotFoundException.
 * The gate must return {@code false} for every legacy {@code 1.x} host and
 * {@code true} for the 26.1+ year-based scheme.
 */
class RetromodPreLaunchTest {

    @Test
    @DisplayName("26.1+ year-scheme hosts are unobfuscated (remap applies)")
    void unobfuscatedFor26Plus() {
        assertTrue(RetromodPreLaunch.isUnobfuscatedTarget("26.1"));
        assertTrue(RetromodPreLaunch.isUnobfuscatedTarget("26.1.2"));   // #24 host
        assertTrue(RetromodPreLaunch.isUnobfuscatedTarget("26.1.2.60")); // NeoForge build str
        assertTrue(RetromodPreLaunch.isUnobfuscatedTarget("27.0"));     // future year
    }

    @Test
    @DisplayName("Legacy 1.x hosts are obfuscated (remap skipped) - #21/#29")
    void obfuscatedForLegacy1x() {
        // The exact hosts from the bug reports:
        assertFalse(RetromodPreLaunch.isUnobfuscatedTarget("1.21.8"), "#21 host");
        assertFalse(RetromodPreLaunch.isUnobfuscatedTarget("1.21.1"), "#29 host");
        // Plus the rest of the legacy range Retromod still supports.
        assertFalse(RetromodPreLaunch.isUnobfuscatedTarget("1.20.1"));
        assertFalse(RetromodPreLaunch.isUnobfuscatedTarget("1.21.11"));
        assertFalse(RetromodPreLaunch.isUnobfuscatedTarget("1.16.5"));
        assertFalse(RetromodPreLaunch.isUnobfuscatedTarget("1.12.2"));
    }

    @Test
    @DisplayName("Unknown / unparseable versions default to true (preserve 26.1 behavior)")
    void defaultsToUnobfuscatedOnUnknown() {
        assertTrue(RetromodPreLaunch.isUnobfuscatedTarget(null));
        assertTrue(RetromodPreLaunch.isUnobfuscatedTarget("unknown"));
        assertTrue(RetromodPreLaunch.isUnobfuscatedTarget(""));
    }

    @Test
    @DisplayName("Leading/trailing whitespace and suffixes are tolerated")
    void tolerateWhitespaceAndSuffix() {
        assertTrue(RetromodPreLaunch.isUnobfuscatedTarget("  26.1.2  "));
        assertFalse(RetromodPreLaunch.isUnobfuscatedTarget(" 1.21.8 "));
        assertTrue(RetromodPreLaunch.isUnobfuscatedTarget("26.1.2-rc1"));
    }

    @Test
    @DisplayName("Shim gate: a shim is skipped when its target exceeds the host (#31/#32/#35)")
    void shimVersionGate() {
        // The 1.21.11→26.1 shim (target 26.1) must not apply on a pre-26.1 host -
        // that's what was renaming Fabric API classes to 26.1 names and crashing mods.
        assertTrue(RetromodPreLaunch.mcVersionExceeds("26.1", "1.21.8"));
        assertTrue(RetromodPreLaunch.mcVersionExceeds("26.1", "1.21.1"));
        // Newer point-release shims are skipped too (don't translate forward past host).
        assertTrue(RetromodPreLaunch.mcVersionExceeds("1.21.11", "1.21.8"));
        // Same-or-older targets apply.
        assertFalse(RetromodPreLaunch.mcVersionExceeds("1.21.8", "1.21.8"));
        assertFalse(RetromodPreLaunch.mcVersionExceeds("1.20.6", "1.21.1")); // the ResourceLocation-ctor shim
        assertFalse(RetromodPreLaunch.mcVersionExceeds("1.16.5", "1.21.8"));
        // On the published 26.1.2 host EVERY shim (max target 26.1) still applies: path unchanged.
        assertFalse(RetromodPreLaunch.mcVersionExceeds("26.1", "26.1.2"));
        assertFalse(RetromodPreLaunch.mcVersionExceeds("1.21.11", "26.1.2"));
        // Unparseable → never over-exclude.
        assertFalse(RetromodPreLaunch.mcVersionExceeds("weird", "1.21.8"));
    }

    @Test
    @DisplayName("compareMcVersions sorts the 26.x year-scheme above legacy 1.x")
    void compareOrdering() {
        assertTrue(RetromodPreLaunch.compareMcVersions("26.1", "1.21.8") > 0);
        assertTrue(RetromodPreLaunch.compareMcVersions("1.21.8", "1.21.11") < 0);
        assertEquals(0, RetromodPreLaunch.compareMcVersions("1.21", "1.21.0"));
        assertEquals(0, RetromodPreLaunch.compareMcVersions("26.1.2", "26.1.2"));
    }

    @Test
    @DisplayName("Real shims: 26.1 shim gated out on a 1.21.8 host; 1.21 shim stays")
    void realShimGating() {
        java.util.Map<String, String> targets = new java.util.HashMap<>();
        for (VersionShim s : java.util.ServiceLoader.load(VersionShim.class)) {
            targets.put(s.getClass().getSimpleName(), s.getTargetVersion());
        }
        // The 1.21.11→26.1 shim, the one renaming Fabric API to 26.1-only names
        // (BeforeExtract, ExtendedMenuProvider), must be skipped on a 1.21.8 host (#31/#32/#35).
        String t261 = targets.get("Fabric_1_21_11_to_26_1");
        assertNotNull(t261, "the 1.21.11→26.1 shim should be registered");
        assertTrue(RetromodPreLaunch.mcVersionExceeds(t261, "1.21.8"),
                "the 26.1 shim must be gated out on a 1.21.8 host");
        // The 1.20.6→1.21 shim (carries the ResourceLocation-ctor redirect) must still apply.
        String t121 = targets.get("Fabric_1_20_6_to_1_21");
        assertNotNull(t121, "the 1.20.6→1.21 shim should be registered");
        assertFalse(RetromodPreLaunch.mcVersionExceeds(t121, "1.21.8"),
                "the 1.20.6→1.21 shim must still apply on a 1.21.8 host");
        // And on the published 26.1.2 host, the 26.1 shim still applies (path unchanged).
        assertFalse(RetromodPreLaunch.mcVersionExceeds(t261, "26.1.2"));
    }
}
