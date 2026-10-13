/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The trade pack's JSON, which the store writes without a JSON library because it is embedded into mods. */
class LegacyTradeStoreTest {

    @Test
    @DisplayName("a tag is written with each entry optional, so a stale entry cannot fail vanilla's tag")
    void tagJson() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", "mod:legacy/minecraft/cleric/level_5/0");
        entry.put("required", false);
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("replace", false);
        tag.put("values", List.of(entry));

        assertEquals("{\"replace\":false,\"values\":[{\"id\":\"mod:legacy/minecraft/cleric/level_5/0\","
                + "\"required\":false}]}", LegacyTradeStore.json(tag));
    }

    @Test
    @DisplayName("quotes and backslashes in strings are escaped")
    void escapesStrings() {
        assertEquals("\"a\\\"b\\\\c\"", LegacyTradeStore.json("a\"b\\c"));
    }
}
