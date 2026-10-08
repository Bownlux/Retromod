/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** #264: Pyrologer And Friends' 1.19.2 smithing upgrades failed to load on 1.20.1. */
class LegacySmithingRecipeMigratorTest {

    private static final String OLD_RECIPE = """
            {"type": "minecraft:smithing",
             "base": {"item": "pyrologernfriends:diamond_dagger"},
             "addition": {"item": "minecraft:netherite_ingot"},
             "result": {"item": "pyrologernfriends:netherite_dagger"}}
            """;

    private static Path writeRecipe(Path jar, String name, String json) throws Exception {
        Path file = jar.resolve("data/pyrologernfriends/recipes/" + name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static JsonObject read(Path file) throws Exception {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    @Test
    @DisplayName("a 1.19.2 smithing recipe becomes a smithing transform with an empty template")
    void legacySmithingBecomesTransform(@TempDir Path jar) throws Exception {
        Path recipe = writeRecipe(jar, "netherite_dagger_smith.json", OLD_RECIPE);

        assertEquals(1, LegacySmithingRecipeMigrator.migrate(jar, "1.19.2", "1.20.1"));

        JsonObject converted = read(recipe);
        assertEquals("minecraft:smithing_transform", converted.get("type").getAsString());
        assertTrue(converted.getAsJsonArray("template").isEmpty(),
                "an empty template matches the empty template slot, as the old recipe had none");
        assertEquals("pyrologernfriends:diamond_dagger",
                converted.getAsJsonObject("base").get("item").getAsString());
        assertEquals("pyrologernfriends:netherite_dagger",
                converted.getAsJsonObject("result").get("item").getAsString());
    }

    @Test
    @DisplayName("a mod built for 1.20, a host older than 1.20 or a 1.20.5+ host is left alone")
    void versionGates(@TempDir Path jar) throws Exception {
        Path recipe = writeRecipe(jar, "dagger.json", OLD_RECIPE);

        assertEquals(0, LegacySmithingRecipeMigrator.migrate(jar, "1.20.1", "1.20.1"));
        assertEquals(0, LegacySmithingRecipeMigrator.migrate(jar, "1.19.2", "1.19.4"));
        assertEquals(0, LegacySmithingRecipeMigrator.migrate(jar, "1.18.2", "1.21.1"),
                "1.20.5 changed the result format, so this conversion would still fail there");
        assertEquals("minecraft:smithing", read(recipe).get("type").getAsString());
    }

    @Test
    @DisplayName("#311: a 1.18.2 smithing recipe also converts on 1.20.1")
    void legacy118SmithingBecomesTransform(@TempDir Path jar) throws Exception {
        Path recipe = jar.resolve("data/isleofberk/recipes/gronckle_iron_sword.json");
        Files.createDirectories(recipe.getParent());
        Files.writeString(recipe, """
                {"type": "minecraft:smithing",
                 "base": {"item": "minecraft:iron_sword"},
                 "addition": {"item": "isleofberk:gronckle_iron"},
                 "result": {"item": "isleofberk:gronckle_iron_sword"}}""", StandardCharsets.UTF_8);

        assertEquals(1, LegacySmithingRecipeMigrator.migrate(jar, "1.18.2", "1.20.1"));
        JsonObject converted = read(recipe);
        assertEquals("minecraft:smithing_transform", converted.get("type").getAsString());
        assertTrue(converted.getAsJsonArray("template").isEmpty());
        assertEquals("isleofberk:gronckle_iron",
                converted.getAsJsonObject("addition").get("item").getAsString());
    }

    @Test
    @DisplayName("other recipes and smithing recipes that already have a template are untouched")
    void otherRecipesUntouched(@TempDir Path jar) throws Exception {
        String shaped = "{\"type\": \"minecraft:crafting_shaped\", \"result\": {\"item\": \"a:b\"}}";
        Path other = writeRecipe(jar, "shaped.json", shaped);

        assertEquals(0, LegacySmithingRecipeMigrator.migrate(jar, "1.19.2", "1.20.1"));
        assertEquals(shaped, Files.readString(other));
        assertNull(LegacySmithingRecipeMigrator.convert(JsonParser.parseString(
                "{\"type\":\"minecraft:smithing\",\"template\":{},\"base\":{},\"addition\":{},"
                        + "\"result\":{}}").getAsJsonObject()));
    }
}
