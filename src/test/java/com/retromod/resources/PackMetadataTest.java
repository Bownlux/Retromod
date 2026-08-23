/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackMetadataTest {

    @Test
    void parsesLegacyAndModernMetadata() throws Exception {
        PackMetadata.DeclaredFormats legacy = PackMetadata.parse(
            "{\"pack\":{\"pack_format\":46,\"supported_formats\":[42,64]}}",
            "legacy");
        assertEquals(new PackFormat(42, 0), legacy.minimum());
        assertEquals(new PackFormat(64, 0), legacy.maximum());
        assertEquals(new PackFormat(46, 0), legacy.primary());

        PackMetadata.DeclaredFormats modern = PackMetadata.parse(
            "{\"pack\":{\"min_format\":[94,1],\"max_format\":[101,1]}}",
            "modern");
        assertEquals(new PackFormat(94, 1), modern.minimum());
        assertEquals(new PackFormat(101, 1), modern.maximum());
        assertEquals(new PackFormat(101, 1), modern.primary());
        assertTrue(modern.supports(new PackFormat(100, 0)));
    }

    @Test
    void writesModernMetadataAndPreservesUnrelatedFields(@TempDir Path pack) throws Exception {
        Files.writeString(pack.resolve("pack.mcmeta"), """
            {
              "pack": {
                "pack_format": 46,
                "supported_formats": [36, 46],
                "description": {"text": "Original description"},
                "custom": true
              },
              "filter": {"block": [{"namespace": "private"}]}
            }
            """);

        PackMetadata.rewrite(pack, new PackFormat(94, 1), true);

        JsonObject root = JsonParser.parseString(Files.readString(pack.resolve("pack.mcmeta")))
            .getAsJsonObject();
        JsonObject metadata = root.getAsJsonObject("pack");
        assertFalse(metadata.has("pack_format"));
        assertFalse(metadata.has("supported_formats"));
        assertEquals(94, metadata.getAsJsonArray("min_format").get(0).getAsInt());
        assertEquals(1, metadata.getAsJsonArray("min_format").get(1).getAsInt());
        assertEquals(metadata.get("min_format"), metadata.get("max_format"));
        assertEquals("Original description",
            metadata.getAsJsonObject("description").get("text").getAsString());
        assertTrue(metadata.get("custom").getAsBoolean());
        assertTrue(root.has("filter"));
    }

    @Test
    void writesLegacyMetadataAndRemovesModernRange(@TempDir Path pack) throws Exception {
        Files.writeString(pack.resolve("pack.mcmeta"), """
            {"pack":{"description":"Old pack","min_format":[69,0],"max_format":[75,0]}}
            """);

        PackMetadata.rewrite(pack, new PackFormat(22, 0), false);

        JsonObject metadata = JsonParser.parseString(Files.readString(pack.resolve("pack.mcmeta")))
            .getAsJsonObject().getAsJsonObject("pack");
        assertEquals(22, metadata.get("pack_format").getAsInt());
        assertEquals("Old pack", metadata.get("description").getAsString());
        assertFalse(metadata.has("min_format"));
        assertFalse(metadata.has("max_format"));
        assertFalse(metadata.has("supported_formats"));
    }

    @Test
    void refusesIncompleteOrReversedRanges() {
        assertThrows(Exception.class, () -> PackMetadata.parse(
            "{\"pack\":{\"min_format\":[69,0]}}", "missing maximum"));
        assertThrows(Exception.class, () -> PackMetadata.parse(
            "{\"pack\":{\"min_format\":[75,0],\"max_format\":[69,0]}}",
            "reversed"));
    }

    @Test
    void directParsingEnforcesTheMetadataByteLimit() {
        String oversized = "é".repeat((int) PackArchive.MAX_METADATA_BYTES / 2 + 1);

        assertThrows(Exception.class, () -> PackMetadata.parse(oversized, "oversized"));
    }

    @Test
    void refusesPrimaryOutsideLegacySupportedFormats() throws Exception {
        assertThrows(Exception.class, () -> PackMetadata.parse(
            "{\"pack\":{\"pack_format\":46,\"supported_formats\":[47,64]}}",
            "below range"));
        assertThrows(Exception.class, () -> PackMetadata.parse(
            "{\"pack\":{\"pack_format\":65,\"supported_formats\":[47,64]}}",
            "above range"));
        assertThrows(Exception.class, () -> PackMetadata.parse(
            "{\"pack\":{\"pack_format\":46,\"supported_formats\":47}}",
            "mismatched exact"));

        PackMetadata.DeclaredFormats lowerBoundary = PackMetadata.parse(
            "{\"pack\":{\"pack_format\":47,\"supported_formats\":[47,64]}}",
            "lower boundary");
        PackMetadata.DeclaredFormats upperBoundary = PackMetadata.parse(
            "{\"pack\":{\"pack_format\":64,\"supported_formats\":[47,64]}}",
            "upper boundary");
        assertEquals(new PackFormat(47, 0), lowerBoundary.primary());
        assertEquals(new PackFormat(64, 0), upperBoundary.primary());
    }

    @Test
    void refusesOverlayRangesInsteadOfClaimingTheyWereTranslated(@TempDir Path pack)
            throws Exception {
        Files.writeString(pack.resolve("pack.mcmeta"), """
            {"pack":{"pack_format":46,"description":"x"},"overlays":{"entries":[]}}
            """);

        assertThrows(Exception.class,
            () -> PackMetadata.rewrite(pack, new PackFormat(88, 0), true));
    }
}
