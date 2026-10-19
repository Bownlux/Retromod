/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelElementFieldMigratorTest {

    private static final String TORCH = "assets/oldmod/models/block/glow_torch.json";
    private static final String GLASS = "assets/oldmod/models/block/stained_glass.json";

    private final ModelElementFieldMigrator converter = new ModelElementFieldMigrator();

    private static MigrationContext context(String source, String target, MigrationContext.Loader loader) {
        return MigrationContext.builder(target).sourceMcVersion(source).targetLoader(loader)
                .declaredNamespaces(List.of("oldmod")).build().forRun(List.of("oldmod"));
    }

    private int run(MapResourceTree tree, MigrationContext context) throws IOException {
        return converter.appliesTo(context) ? converter.apply(tree, context) : 0;
    }

    private static JsonObject json(MapResourceTree tree, String name) throws IOException {
        return JsonParser.parseString(text(tree.read(name))).getAsJsonObject();
    }

    private static Map<String, byte[]> unshadedTorch() {
        return entries(TORCH, "{\"elements\":[{\"from\":[7,0,7],\"to\":[9,10,9],\"shade\":false,\"faces\":{}},"
                + "{\"from\":[0,0,0],\"to\":[1,1,1],\"shade\":true,\"faces\":{}},"
                + "{\"from\":[0,0,0],\"to\":[1,1,1],\"faces\":{}}]}");
    }

    @Test
    @DisplayName("On 26.3 an unshaded element gets the up override, which lights it the same way")
    void shadeFalseOn263() throws IOException {
        MapResourceTree tree = new MapResourceTree(unshadedTorch());

        assertEquals(1, run(tree, context("1.12.2", "26.3", MigrationContext.Loader.FABRIC)));

        var elements = json(tree, TORCH).getAsJsonArray("elements");
        JsonObject unshaded = elements.get(0).getAsJsonObject();
        assertEquals("up", unshaded.get("shade_direction_override").getAsString());
        assertFalse(unshaded.has("shade"), "26.3 no longer reads shade");
        assertTrue(elements.get(1).getAsJsonObject().get("shade").getAsBoolean(),
                "a shaded element is shaded either way, so it is left as written");
        assertFalse(elements.get(2).getAsJsonObject().has("shade_direction_override"));
    }

    @Test
    @DisplayName("26.2 still reads shade, so a 26.2 host keeps the model as shipped")
    void shadeUnchangedOn262() throws IOException {
        Map<String, byte[]> shipped = unshadedTorch();
        MapResourceTree tree = new MapResourceTree(shipped);

        assertEquals(0, run(tree, context("1.12.2", "26.2", MigrationContext.Loader.FABRIC)));
        assertArrayEquals(shipped.get(TORCH), tree.read(TORCH));
    }

    @Test
    @DisplayName("An element that already has an override keeps it")
    void existingOverrideKept() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(TORCH,
                "{\"elements\":[{\"shade\":false,\"shade_direction_override\":\"north\",\"faces\":{}}]}"));

        assertEquals(0, run(tree, context("26.2", "26.3", MigrationContext.Loader.NEOFORGE)));
    }

    @ParameterizedTest(name = "{0} on {1} {2}: converted={3}")
    @CsvSource({
            "1.20.1, 26.1, NEOFORGE, true",
            "1.20.1, 26.3, NEOFORGE, true",
            "1.20.1, 1.21.11, NEOFORGE, false",
            "1.20.1, 26.3, FORGE, false",
            "26.1, 26.3, NEOFORGE, false"
    })
    @DisplayName("render_type translucent becomes force_translucent sprites where NeoForge stopped reading it")
    void translucentRenderType(String source, String target, String loader, boolean converted)
            throws IOException {
        Map<String, byte[]> shipped = entries(GLASS,
                "{\"parent\":\"minecraft:block/cube_all\",\"render_type\":\"minecraft:translucent\","
                        + "\"textures\":{\"all\":\"oldmod:block/stained_glass\",\"particle\":\"oldmod:block/glass\","
                        + "\"edge\":\"#all\"}}");
        MapResourceTree tree = new MapResourceTree(shipped);

        run(tree, context(source, target, MigrationContext.Loader.valueOf(loader)));

        if (!converted) {
            assertArrayEquals(shipped.get(GLASS), tree.read(GLASS));
            return;
        }
        JsonObject textures = json(tree, GLASS).getAsJsonObject("textures");
        assertEquals(JsonParser.parseString("{\"sprite\":\"oldmod:block/stained_glass\",\"force_translucent\":true}"),
                textures.get("all"));
        assertEquals("oldmod:block/glass", textures.get("particle").getAsString(), "particles keep the plain form");
        assertEquals("#all", textures.get("edge").getAsString(), "a reference follows the slot it names");
    }

    @Test
    @DisplayName("A child inherits its parent's translucent render_type; cutout is left to the alpha rule")
    void inheritedAndOtherRenderTypes() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/models/block/glass_base.json",
                "{\"parent\":\"minecraft:block/cube_all\",\"render_type\":\"translucent\"}",
                GLASS, "{\"parent\":\"oldmod:block/glass_base\",\"textures\":{\"all\":\"oldmod:block/glass\"}}",
                "assets/oldmod/models/block/leaves.json",
                "{\"render_type\":\"cutout_mipped\",\"textures\":{\"all\":\"oldmod:block/leaves\"}}"));

        assertEquals(1, run(tree, context("1.20.1", "26.3", MigrationContext.Loader.NEOFORGE)));

        JsonElement all = json(tree, GLASS).getAsJsonObject("textures").get("all");
        assertTrue(all.isJsonObject() && all.getAsJsonObject().get("force_translucent").getAsBoolean());
        assertEquals("oldmod:block/leaves", json(tree, "assets/oldmod/models/block/leaves.json")
                .getAsJsonObject("textures").get("all").getAsString());
    }

    @Test
    @DisplayName("A second run changes nothing")
    void idempotent() throws IOException {
        MapResourceTree tree = new MapResourceTree(unshadedTorch());
        tree.write(GLASS, bytes("{\"render_type\":\"translucent\",\"textures\":{\"all\":\"oldmod:block/glass\"}}"));
        MigrationContext context = context("1.20.1", "26.3", MigrationContext.Loader.NEOFORGE);
        assertEquals(2, run(tree, context));
        Map<String, String> once = gameResourceDigests(tree.entries());

        assertEquals(0, run(tree, context));
        assertEquals(once, gameResourceDigests(tree.entries()));
    }
}
