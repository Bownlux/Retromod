/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Number providers from mods built before 26.3, rewritten for its whole-number and fraction kinds. */
class LegacyNumberProvidersTest {

    @Test
    @DisplayName("a scaled score in a fraction field becomes the whole score times its scale")
    void scaledScoreInFractionField() throws IOException {
        assertJson("""
                [{"function":"minecraft:set_attributes","modifiers":[{"attribute":"minecraft:movement_speed",
                  "amount":{"type":"minecraft:mul","inputs":[{"type":"minecraft:from_int","input":
                    {"type":"minecraft:score","target":{"type":"minecraft:fixed","name":"$speed"},"score":"dummy"}},
                    0.04]},"operation":"add_value","slot":"head"}]}]""",
                convert("data/mod/item_modifier/helm.json", """
                [{"function":"minecraft:set_attributes","modifiers":[{"attribute":"minecraft:movement_speed",
                  "amount":{"type":"minecraft:score","target":{"type":"minecraft:fixed","name":"$speed"},
                    "score":"dummy","scale":0.04},"operation":"add_value","slot":"head"}]}]""", "26.3"));
    }

    @Test
    @DisplayName("a binomial in a fraction field is read through from_int, with its trials rounded as before")
    void binomialInFractionField() throws IOException {
        assertJson("""
                {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"minecraft:cake","functions":[
                  {"function":"minecraft:set_damage","damage":{"type":"minecraft:from_int","input":
                    {"type":"minecraft:binomial","n":3,"p":0.65}}}]}]}]}""",
                convert("data/mod/loot_table/cake.json", """
                {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"minecraft:cake","functions":[
                  {"function":"minecraft:set_damage","damage":{"type":"minecraft:binomial","n":2.5,"p":0.65}}]}]}]}""",
                        "26.3"));
    }

    @Test
    @DisplayName("a whole-number field keeps its score and binomial, and a range's bound that is a range becomes a uniform")
    void wholeNumberFieldsAndRangeBounds() throws IOException {
        String count = """
                {"pools":[{"rolls":{"type":"minecraft:binomial","n":3,"p":0.5},"entries":[]}]}""";
        assertJson(count, convert("data/mod/loot_table/rolls.json", count, "26.3"));
        assertJson("""
                {"condition":"minecraft:entity_scores","entity":"this","scores":{"random":{"min":1,
                  "max":{"type":"minecraft:uniform","min":1,"max":{"type":"minecraft:score",
                  "target":{"type":"minecraft:fixed","name":"$random"},"score":"dummy"}}}}}""",
                convert("data/mod/predicate/random.json", """
                {"condition":"minecraft:entity_scores","entity":"this","scores":{"random":{"min":1,
                  "max":{"min":1,"max":{"type":"minecraft:score","target":{"type":"minecraft:fixed","name":"$random"},
                  "score":"dummy"}}}}}""", "26.3"));
    }

    @Test
    @DisplayName("data built for 26.3, or running on 26.2, is left alone")
    void gatedOnSourceAndTarget() {
        LegacyNumberProviders converter = new LegacyNumberProviders();
        assertFalse(converter.appliesTo(context("26.3", "26.3")));
        assertFalse(converter.appliesTo(context("1.21.1", "26.2")));
        assertFalse(converter.appliesTo(context(null, "26.3")));
    }

    private static String convert(String name, String json, String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(Map.of(name, json.getBytes(StandardCharsets.UTF_8)));
        LegacyNumberProviders converter = new LegacyNumberProviders();
        MigrationContext context = context("1.21.1", target);
        if (converter.appliesTo(context)) converter.apply(tree, context);
        return new String(tree.read(name), StandardCharsets.UTF_8);
    }

    private static MigrationContext context(String source, String target) {
        return MigrationContext.builder(target).sourceMcVersion(source)
                .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(List.of("mod")).build()
                .forRun(List.of("mod", "minecraft"));
    }

    private static void assertJson(String expected, String actual) {
        JsonElement want = JsonParser.parseString(expected);
        assertEquals(want, JsonParser.parseString(actual), actual);
    }
}
