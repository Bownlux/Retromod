/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuiltMetadataCompatTest {

    @Test
    @DisplayName("real Quilt dependency arrays expose and update the Minecraft range")
    void readsAndUpdatesQuiltLoaderDependencies() throws Exception {
        byte[] original = metadata(">=1.20").getBytes(StandardCharsets.UTF_8);

        assertEquals(">=1.20", QuiltMetadataCompat.readMinecraftVersion(original));

        byte[] updated = QuiltMetadataCompat.updateMinecraftVersion(original, "26.1");
        JsonObject root = JsonParser.parseString(new String(updated, StandardCharsets.UTF_8))
            .getAsJsonObject();
        JsonObject loader = root.getAsJsonObject("quilt_loader");
        JsonArray dependencies = loader.getAsJsonArray("depends");

        assertEquals("retromod.example", loader.get("group").getAsString());
        assertEquals(">=0.20.0", dependencies.get(0).getAsJsonObject()
            .get("versions").getAsString());
        assertEquals("=26.1", dependencies.get(1).getAsJsonObject()
            .get("versions").getAsString());
    }

    @Test
    @DisplayName("bounded stream reads reject oversized Quilt metadata")
    void rejectsOversizedMetadata() {
        byte[] oversized = new byte[(int) QuiltMetadataCompat.MAX_METADATA_BYTES + 1];
        assertThrows(IOException.class, () -> QuiltMetadataCompat.readMinecraftVersion(
            new ByteArrayInputStream(oversized)));
        String oversizedText = " ".repeat((int) QuiltMetadataCompat.MAX_METADATA_BYTES + 1);
        assertThrows(IOException.class,
            () -> QuiltMetadataCompat.updateMinecraftVersion(oversizedText, "26.1"));
    }

    @Test
    @DisplayName("deep Quilt metadata is refused before Gson walks it")
    void rejectsDeepMetadata() {
        String deeplyNested = "[".repeat(300) + "0" + "]".repeat(300);
        assertThrows(IOException.class, () -> QuiltMetadataCompat.readMinecraftVersion(
            deeplyNested.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("metadata without a Minecraft dependency stays unchanged")
    void leavesMissingDependencyUnchanged() throws Exception {
        byte[] metadata = """
            {"quilt_loader":{"depends":[{"id":"quilt_loader","versions":">=0.20.0"}]}}
            """.getBytes(StandardCharsets.UTF_8);

        assertNull(QuiltMetadataCompat.readMinecraftVersion(metadata));
        assertSame(metadata, QuiltMetadataCompat.updateMinecraftVersion(metadata, "26.1"));
    }

    @Test
    @DisplayName("unsafe target values cannot enter Quilt metadata")
    void rejectsUnsafeTargetVersion() {
        byte[] original = metadata(">=1.20").getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class,
            () -> QuiltMetadataCompat.updateMinecraftVersion(original, "26.1\ninvalid"));
    }

    @Test
    @DisplayName("complex and repeated Minecraft constraints are all updated")
    void updatesEverySupportedDependencyShape() throws Exception {
        byte[] original = """
            {
              "schema_version": 1,
              "quilt_loader": {
                "depends": [
                  {"id":"minecraft","versions":[">=1.20","<1.21"]},
                  [
                    {"id":"alternate","versions":"*"},
                    {"id":"minecraft","versions":{"all":[">=1.20","<1.21"]}}
                  ]
                ]
              }
            }
            """.getBytes(StandardCharsets.UTF_8);

        assertNull(QuiltMetadataCompat.readMinecraftVersion(original));
        byte[] updated = QuiltMetadataCompat.updateMinecraftVersion(original, "26.2");
        JsonArray dependencies = JsonParser.parseString(
                new String(updated, StandardCharsets.UTF_8)).getAsJsonObject()
            .getAsJsonObject("quilt_loader").getAsJsonArray("depends");

        assertEquals("=26.2", dependencies.get(0).getAsJsonObject()
            .get("versions").getAsString());
        assertEquals("=26.2", dependencies.get(1).getAsJsonArray().get(1)
            .getAsJsonObject().get("versions").getAsString());
        assertEquals("=26.2", QuiltMetadataCompat.readMinecraftVersion(updated));
    }

    @Test
    @DisplayName("string Minecraft dependencies keep their array structure and become exact")
    void updatesStringDependenciesWithoutFlatteningAlternatives() throws Exception {
        byte[] original = """
            {
              "schema_version": 1,
              "quilt_loader": {
                "depends": [
                  "minecraft",
                  [
                    "alternate",
                    "builtin:minecraft"
                  ],
                  {"id":"library","versions":{"any":["1","2"]}}
                ]
              }
            }
            """.getBytes(StandardCharsets.UTF_8);

        assertEquals("*", QuiltMetadataCompat.readMinecraftVersion(original));
        byte[] updated = QuiltMetadataCompat.updateMinecraftVersion(original, "26.2");
        JsonArray dependencies = JsonParser.parseString(
                new String(updated, StandardCharsets.UTF_8)).getAsJsonObject()
            .getAsJsonObject("quilt_loader").getAsJsonArray("depends");

        JsonObject direct = dependencies.get(0).getAsJsonObject();
        assertEquals("minecraft", direct.get("id").getAsString());
        assertEquals("=26.2", direct.get("versions").getAsString());

        JsonArray alternatives = dependencies.get(1).getAsJsonArray();
        assertEquals("alternate", alternatives.get(0).getAsString());
        JsonObject grouped = alternatives.get(1).getAsJsonObject();
        assertEquals("builtin:minecraft", grouped.get("id").getAsString());
        assertEquals("=26.2", grouped.get("versions").getAsString());

        JsonObject unrelated = dependencies.get(2).getAsJsonObject();
        assertEquals("library", unrelated.get("id").getAsString());
        assertEquals(2, unrelated.getAsJsonObject("versions")
            .getAsJsonArray("any").size());
        assertEquals("=26.2", QuiltMetadataCompat.readMinecraftVersion(updated));
    }

    private static String metadata(String minecraftVersion) {
        return """
            {
              "schema_version": 1,
              "quilt_loader": {
                "group": "retromod.example",
                "id": "example",
                "depends": [
                  {"id": "quilt_loader", "versions": ">=0.20.0"},
                  {"id": "minecraft", "versions": "%s"}
                ]
              }
            }
            """.formatted(minecraftVersion);
    }
}
