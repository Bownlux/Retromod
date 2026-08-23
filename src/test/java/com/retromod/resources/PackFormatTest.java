/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackFormatTest {

    @Test
    void everyPublishedHostUsesItsExactPackFormats() {
        Map<String, int[]> expected = Map.ofEntries(
            entry("1.20", 15, 0, 15, 0),
            entry("1.20.1", 15, 0, 15, 0),
            entry("1.20.2", 18, 0, 18, 0),
            entry("1.20.3", 22, 0, 26, 0),
            entry("1.20.4", 22, 0, 26, 0),
            entry("1.20.5", 32, 0, 41, 0),
            entry("1.20.6", 32, 0, 41, 0),
            entry("1.21", 34, 0, 48, 0),
            entry("1.21.1", 34, 0, 48, 0),
            entry("1.21.2", 42, 0, 57, 0),
            entry("1.21.3", 42, 0, 57, 0),
            entry("1.21.4", 46, 0, 61, 0),
            entry("1.21.5", 55, 0, 71, 0),
            entry("1.21.6", 63, 0, 80, 0),
            entry("1.21.7", 64, 0, 81, 0),
            entry("1.21.8", 64, 0, 81, 0),
            entry("1.21.9", 69, 0, 88, 0),
            entry("1.21.10", 69, 0, 88, 0),
            entry("1.21.11", 75, 0, 94, 1),
            entry("26.1", 84, 0, 101, 1),
            entry("26.1.1", 84, 0, 101, 1),
            entry("26.1.2", 84, 0, 101, 1),
            entry("26.2", 88, 0, 107, 1)
        );

        assertEquals(23, expected.size());
        expected.forEach((version, formats) -> {
            assertEquals(new PackFormat(formats[0], formats[1]),
                PackFormat.resourceTarget(version), "resource format for " + version);
            assertEquals(new PackFormat(formats[2], formats[3]),
                PackFormat.dataTarget(version), "data format for " + version);
        });
    }

    @Test
    void unknownTargetsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new ResourcePackTransformer("1.22"));
        assertThrows(IllegalArgumentException.class,
            () -> new DataPackTransformer("unknown"));
    }

    private static Map.Entry<String, int[]> entry(String version,
                                                   int resourceMajor,
                                                   int resourceMinor,
                                                   int dataMajor,
                                                   int dataMinor) {
        return Map.entry(version,
            new int[]{resourceMajor, resourceMinor, dataMajor, dataMinor});
    }
}
