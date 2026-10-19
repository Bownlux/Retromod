/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class SourcePackFormatTest {

    @ParameterizedTest(name = "{0} shipped resource format {1}")
    @CsvSource({
            "1.8.9, 1", "1.10.2, 2", "1.12.2, 3", "1.13, 4", "1.14.4, 4", "1.16.1, 5", "1.16.5, 6",
            "1.17.1, 7", "1.18.2, 8", "1.19.2, 9", "1.19.3, 12", "1.19.4, 13", "1.20.1, 15", "1.21.1, 34"
    })
    @DisplayName("A mod's Minecraft version gives the resource format its assets were written for")
    void formatsByVersion(String version, int format) {
        assertEquals(new PackFormat(format, 0), SourcePackFormat.resource(version));
    }

    @Test
    @DisplayName("26.3 carries its minor format, and versions that name no release give nothing")
    void newestAndUnknown() {
        assertEquals(new PackFormat(97, 1), SourcePackFormat.resource("26.3"));
        assertNull(SourcePackFormat.resource(null));
        assertNull(SourcePackFormat.resource("${mcversion}"));
        assertNull(SourcePackFormat.resource("1.5.2"), "older than resource packs");
    }
}
