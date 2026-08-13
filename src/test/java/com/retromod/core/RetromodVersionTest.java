/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link RetromodVersion#sameMinorVersion}: the guard that stops the automatic
 * in-place mod scan from re-transforming mods that differ from the host only by a patch
 * (#60: a "1.21" mod on a "1.21.1" host was being needlessly transformed, which then
 * broke it via API shims it never needed).
 */
class RetromodVersionTest {

    @Test
    @DisplayName("#60: a bare-minor mod matches a patch host (1.21 ~ 1.21.1) → skip transform")
    void bareMinorMatchesPatchHost() {
        assertTrue(RetromodVersion.sameMinorVersion("1.21", "1.21.1"));
        assertTrue(RetromodVersion.sameMinorVersion("1.21.1", "1.21"));
        assertTrue(RetromodVersion.sameMinorVersion("1.21.8", "1.21.11"));
        assertTrue(RetromodVersion.sameMinorVersion("26.1", "26.1.2"));
    }

    @Test
    @DisplayName("different minor/major is NOT same-minor → still transforms")
    void differentMinorOrMajor() {
        assertFalse(RetromodVersion.sameMinorVersion("1.20.1", "1.21.1")); // older minor
        assertFalse(RetromodVersion.sameMinorVersion("1.16.5", "1.21.1"));
        assertFalse(RetromodVersion.sameMinorVersion("1.21.1", "26.1"));   // different major
    }

    @Test
    @DisplayName("unparseable / too-short versions are NOT treated as same-minor (don't skip)")
    void unparseableNeverSkips() {
        assertFalse(RetromodVersion.sameMinorVersion(null, "1.21.1"));
        assertFalse(RetromodVersion.sameMinorVersion("1.21.1", null));
        assertFalse(RetromodVersion.sameMinorVersion("1", "1.21.1")); // only one component
        assertFalse(RetromodVersion.sameMinorVersion("garbage", "1.21.1"));
    }
}
