/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The #264 late-registration bridge may reopen a Forge registry only while mods are still loading.
 * Once Forge freezes registry data the ids are final, so a late call must fail as it always did.
 */
class LateForgeRegistrationTest {

    /** The lock surface of a Forge registry: {@code isLocked}, {@code unfreeze}, {@code freeze}. */
    public static final class FakeRegistry {
        boolean locked = true;
        public boolean isLocked() { return locked; }
        public void unfreeze() { locked = false; }
        public void freeze() { locked = true; }
    }

    @Test
    @DisplayName("a locked registry is reopened for the call and locked again while loading")
    void reopensWhileLoading() throws Exception {
        FakeRegistry registry = new FakeRegistry();
        boolean[] lockedDuringCall = new boolean[1];

        LateForgeRegistration.invokeReopening(registry, true, () -> lockedDuringCall[0] = registry.locked);

        assertFalse(lockedDuringCall[0], "the static-initializer registration needs the registry open");
        assertTrue(registry.locked, "the registry must be locked again after the call");
    }

    @Test
    @DisplayName("after registry data is frozen the registry stays locked")
    void staysLockedAfterLoading() throws Exception {
        FakeRegistry registry = new FakeRegistry();
        boolean[] lockedDuringCall = new boolean[1];

        LateForgeRegistration.invokeReopening(registry, false, () -> lockedDuringCall[0] = registry.locked);

        assertTrue(lockedDuringCall[0],
                "a registration after FREEZE_DATA must reach Forge's own too-late error, not slip in");
    }

    @Test
    @DisplayName("a failing registration still leaves the registry locked")
    void relocksWhenRegistrationFails() {
        FakeRegistry registry = new FakeRegistry();

        assertThrows(IllegalStateException.class, () -> LateForgeRegistration.invokeReopening(
                registry, true, () -> { throw new IllegalStateException("duplicate id"); }));

        assertTrue(registry.locked, "an exception must not leave the registry open");
    }
}
