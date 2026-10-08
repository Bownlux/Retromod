/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 26.1 made the disk feature's rule-based state provider a typed provider. An old disk without a
 * {@code type} failed with {@code No key type in MapLike} and took the worldgen registry load down.
 */
class LegacyDiskStateProviderMigratorTest {

    private static final String ENTRY = "data/examplemod/worldgen/configured_feature/patch_dirt.json";

    private static final String OLD_DISK = """
            {"type": "minecraft:disk",
             "config": {
               "state_provider": {
                 "fallback": {"type": "minecraft:simple_state_provider",
                              "state": {"Name": "minecraft:coarse_dirt"}},
                 "rules": []
               },
               "half_height": 3,
               "radius": 4,
               "target": {"type": "minecraft:matching_blocks", "blocks": ["minecraft:grass_block"]}
             }}""";

    @Test
    @DisplayName("an untyped disk state provider becomes a rule_based_state_provider with its fields")
    void untypedProviderIsTyped() {
        JsonObject provider = migrated(OLD_DISK).getAsJsonObject("config")
                .getAsJsonObject("state_provider");

        assertEquals("minecraft:rule_based_state_provider", provider.get("type").getAsString());
        assertEquals("minecraft:simple_state_provider",
                provider.getAsJsonObject("fallback").get("type").getAsString(),
                "the fallback provider must be kept as it was");
        assertTrue(provider.getAsJsonArray("rules").isEmpty());
    }

    @Test
    @DisplayName("a disk that already names its provider type is left byte for byte")
    void typedProviderIsUntouched() {
        byte[] in = OLD_DISK.replace("\"fallback\"",
                "\"type\": \"minecraft:rule_based_state_provider\", \"fallback\"")
                .getBytes(StandardCharsets.UTF_8);

        assertSame(in, ModDataMigrator.migrate(ENTRY, in, "26.1.2"));
    }

    @Test
    @DisplayName("an older host keeps the untyped provider it still reads")
    void olderHostIsUntouched() {
        byte[] in = OLD_DISK.getBytes(StandardCharsets.UTF_8);

        assertSame(in, ModDataMigrator.migrate(ENTRY, in, "1.21.1"));
    }

    private static JsonObject migrated(String json) {
        byte[] out = ModDataMigrator.migrate(ENTRY, json.getBytes(StandardCharsets.UTF_8), "26.1.2");
        return JsonParser.parseString(new String(out, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
