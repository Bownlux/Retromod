/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyForgeChannelBuilderTest {

    private enum Status { PRESENT, MISSING, VANILLA }

    @Test
    @DisplayName("Legacy Forge channel predicates receive their advertised string version")
    void restoresAdvertisedProtocolString() {
        String advertised = "cofh-1.0";
        int encoded = advertised.hashCode();

        assertEquals(advertised, LegacyForgeChannelBuilder.legacyVersion(
                new Object[]{Status.PRESENT, encoded}, advertised));
        assertEquals("27", LegacyForgeChannelBuilder.legacyVersion(
                new Object[]{Status.PRESENT, 27}, advertised));
    }

    @Test
    @DisplayName("Legacy Forge channel predicates retain missing and vanilla sentinels")
    void restoresLegacyConnectionSentinels() {
        assertEquals("ABSENT \ud83e\udd14", LegacyForgeChannelBuilder.legacyVersion(
                new Object[]{Status.MISSING, 0}, "1"));
        assertEquals("ALLOWVANILLA \ud83d\udc93\ud83d\udc93\ud83d\udc93",
                LegacyForgeChannelBuilder.legacyVersion(
                        new Object[]{Status.VANILLA, 0}, "1"));
    }
}
