/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Library dependencies of an old Fabric mod become suggestions, so a renamed or missing API does
 * not block launch. A language adapter is different: it constructs the mod's entrypoints, so a mod
 * without it cannot start, and Fabric must be able to name it as missing.
 */
class FabricLanguageAdapterDependencyTest {

    private static final String KOTLIN_MOD = """
            {
              "schemaVersion": 1,
              "id": "kotlinmod",
              "entrypoints": {"client": [{"value": "example.Client", "adapter": "kotlin"}]},
              "depends": {
                "fabricloader": ">=0.16.9",
                "minecraft": ">=1.21.2",
                "java": ">=21",
                "fabric-api": ">=0.106.1+1.21.2",
                "fabric-language-kotlin": ">=1.12.3+kotlin.2.0.21",
                "yet_another_config_lib_v3": "*"
              }
            }
            """;

    @Test
    @DisplayName("the Kotlin language adapter stays required while library dependencies become suggestions")
    void languageAdapterStaysRequired() throws Exception {
        JsonObject root = JsonParser.parseString(patch(KOTLIN_MOD, "1.21.2")).getAsJsonObject();
        JsonObject depends = root.getAsJsonObject("depends");
        JsonObject suggests = root.getAsJsonObject("suggests");

        assertTrue(depends.has("fabric-language-kotlin"),
                "a missing adapter must be reported by Fabric as a missing mod: " + depends);
        assertFalse(suggests.has("fabric-language-kotlin"), suggests.toString());
        assertTrue(suggests.has("yet_another_config_lib_v3"), suggests.toString());
        assertTrue(suggests.has("fabric-api"), suggests.toString());
    }

    @Test
    @DisplayName("an upper-bounded adapter range is relaxed instead of rejecting a newer adapter")
    void adapterRangeIsRelaxed() throws Exception {
        String pinned = KOTLIN_MOD.replace("\">=1.12.3+kotlin.2.0.21\"", "\">=1.12.3 <1.13\"");
        JsonObject depends = JsonParser.parseString(patch(pinned, "1.21.2"))
                .getAsJsonObject().getAsJsonObject("depends");

        assertEquals("*", depends.get("fabric-language-kotlin").getAsString());
    }

    private static String patch(String json, String originalVersion) throws Exception {
        FabricModTransformer transformer = new FabricModTransformer("26.2");
        Method method = FabricModTransformer.class.getDeclaredMethod(
                "updateVersionRequirements", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(transformer, json, originalVersion);
    }
}
