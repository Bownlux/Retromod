/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyBuiltinEntityBlockModelsTest {

    private static final String BLOCKSTATE = "assets/oldmod/blockstates/jar.json";
    private static final String PARTICLE_MODEL = "assets/oldmod/models/custom/jar_particle.json";
    private static final String DISPLAY_MODEL = "assets/oldmod/models/displaysettings/jar.item.json";
    private static final String GENERATED = "assets/oldmod/models/block/retromod/builtin/custom/jar_particle.json";

    private final LegacyBuiltinEntityBlockModels converter = new LegacyBuiltinEntityBlockModels();

    /** The MCreator GeckoLib shape: blockstate, a particle model, display settings, builtin/entity. */
    private static Map<String, byte[]> geckoBlock() {
        return new LinkedHashMap<>(entries(
                BLOCKSTATE, "{\"variants\":{\"facing=north\":{\"model\":\"oldmod:custom/jar_particle\"},"
                        + "\"facing=east\":{\"model\":\"oldmod:custom/jar_particle\",\"y\":90}}}",
                PARTICLE_MODEL, "{\"parent\":\"oldmod:displaysettings/jar.item\","
                        + "\"textures\":{\"particle\":\"oldmod:block/jar_glass\"}}",
                DISPLAY_MODEL, "{\"parent\":\"builtin/entity\",\"display\":{\"gui\":{\"scale\":[0.5,0.5,0.5]}}}"));
    }

    private static MigrationContext context(String source, String target) {
        return MigrationContext.builder(target).sourceMcVersion(source)
                .declaredNamespaces(List.of("oldmod")).build().forRun(List.of("oldmod"));
    }

    private int run(MapResourceTree tree, MigrationContext context) throws IOException {
        return converter.appliesTo(context) ? converter.apply(tree, context) : 0;
    }

    private static JsonObject json(MapResourceTree tree, String name) throws IOException {
        return JsonParser.parseString(text(tree.read(name))).getAsJsonObject();
    }

    @ParameterizedTest(name = "1.20.1 block on {0}")
    @ValueSource(strings = {"1.21.4", "1.21.11", "26.3"})
    @DisplayName("A block drawn by its renderer gets a particle-only model on hosts without builtin/entity")
    void particleOnlyModel(String target) throws IOException {
        Map<String, byte[]> shipped = geckoBlock();
        MapResourceTree tree = new MapResourceTree(shipped);

        assertEquals(2, run(tree, context("1.20.1", target)), "one model written, one blockstate repointed");

        assertEquals(JsonParser.parseString("{\"textures\":{\"particle\":\"oldmod:block/jar_glass\"}}"),
                json(tree, GENERATED));
        JsonObject variants = json(tree, BLOCKSTATE).getAsJsonObject("variants");
        assertEquals("oldmod:block/retromod/builtin/custom/jar_particle",
                variants.getAsJsonObject("facing=north").get("model").getAsString());
        assertEquals(90, variants.getAsJsonObject("facing=east").get("y").getAsInt(), "rotation kept");
        assertArrayEquals(shipped.get(PARTICLE_MODEL), tree.read(PARTICLE_MODEL), "the item still uses it");
        assertArrayEquals(shipped.get(DISPLAY_MODEL), tree.read(DISPLAY_MODEL));
    }

    @Test
    @DisplayName("1.21.1 still loads builtin/entity, so a 1.21.1 host keeps the blockstate as shipped")
    void olderHostUnchanged() throws IOException {
        Map<String, byte[]> shipped = geckoBlock();
        MapResourceTree tree = new MapResourceTree(shipped);

        assertEquals(0, run(tree, context("1.20.1", "1.21.1")));
        assertEquals(gameResourceDigests(shipped), gameResourceDigests(tree.entries()));
        assertFalse(converter.appliesTo(context("1.21.4", "26.3")), "a 1.21.4 mod never used it");
        assertFalse(converter.appliesTo(context(null, "26.3")), "an unknown source is not taken as old");
    }

    @Test
    @DisplayName("A chain with elements of its own is a real model and stays")
    void chainWithElementsUnchanged() throws IOException {
        Map<String, byte[]> shipped = geckoBlock();
        shipped.put(PARTICLE_MODEL, bytes("{\"parent\":\"oldmod:displaysettings/jar.item\","
                + "\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{}}]}"));
        MapResourceTree tree = new MapResourceTree(shipped);

        assertEquals(0, run(tree, context("1.20.1", "26.3")));
        assertFalse(tree.exists(GENERATED));
    }

    @Test
    @DisplayName("With no particle slot, the first texture the chain names is used, following #references")
    void firstTextureWhenNoParticle() throws IOException {
        Map<String, byte[]> shipped = geckoBlock();
        shipped.put(PARTICLE_MODEL, bytes("{\"parent\":\"oldmod:displaysettings/jar.item\","
                + "\"textures\":{\"body\":\"#glass\",\"glass\":\"oldmod:block/jar_body\"}}"));
        MapResourceTree tree = new MapResourceTree(shipped);

        run(tree, context("1.20.1", "26.3"));
        assertEquals("oldmod:block/jar_body",
                json(tree, GENERATED).getAsJsonObject("textures").get("particle").getAsString());
    }

    @Test
    @DisplayName("Multipart blockstates are repointed too, and a second run changes nothing")
    void multipartAndIdempotent() throws IOException {
        Map<String, byte[]> shipped = geckoBlock();
        shipped.put(BLOCKSTATE, bytes("{\"multipart\":[{\"apply\":{\"model\":\"oldmod:custom/jar_particle\"}}]}"));
        MapResourceTree tree = new MapResourceTree(shipped);
        MigrationContext context = context("1.18.2", "26.3");

        assertEquals(2, run(tree, context));
        assertEquals("oldmod:block/retromod/builtin/custom/jar_particle", json(tree, BLOCKSTATE)
                .getAsJsonArray("multipart").get(0).getAsJsonObject().getAsJsonObject("apply")
                .get("model").getAsString());
        Map<String, String> once = gameResourceDigests(tree.entries());

        assertEquals(0, run(tree, context));
        assertEquals(once, gameResourceDigests(tree.entries()));
    }
}
