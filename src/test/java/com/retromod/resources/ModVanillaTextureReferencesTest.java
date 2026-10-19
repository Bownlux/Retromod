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
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ModVanillaTextureReferencesTest {

    private static final String ORE = "assets/oldmod/models/block/ruby_ore.json";

    private final ModVanillaTextureReferences converter = new ModVanillaTextureReferences();

    private static MigrationContext context(String source, String target) {
        return MigrationContext.builder(target).sourceMcVersion(source)
                .declaredNamespaces(List.of("oldmod")).build().forRun(List.of("oldmod"));
    }

    private int run(MapResourceTree tree, MigrationContext context) throws IOException {
        return converter.appliesTo(context) ? converter.apply(tree, context) : 0;
    }

    private static JsonObject textures(MapResourceTree tree, String name) throws IOException {
        return JsonParser.parseString(text(tree.read(name))).getAsJsonObject().getAsJsonObject("textures");
    }

    @Test
    @DisplayName("A 1.12 model's vanilla textures follow the Flattening, written the way the mod wrote them")
    void flatteningRenamesVanillaReferences() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(ORE,
                "{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"blocks/stone\","
                        + "\"side\":\"minecraft:blocks/planks_oak\",\"overlay\":\"oldmod:blocks/brick\","
                        + "\"particle\":\"#all\"}}"));

        assertEquals(1, run(tree, context("1.12.2", "26.3")));

        JsonObject textures = textures(tree, ORE);
        assertEquals("block/stone", textures.get("all").getAsString());
        assertEquals("minecraft:block/oak_planks", textures.get("side").getAsString());
        assertEquals("oldmod:blocks/brick", textures.get("overlay").getAsString(),
                "the mod's own brick is not vanilla's, so it keeps its name and folder");
        assertEquals("#all", textures.get("particle").getAsString());
    }

    @ParameterizedTest(name = "1.18.2 chain on {0}: {1}")
    @CsvSource({
            "1.21.1, minecraft:item/chain",
            "1.21.11, minecraft:item/iron_chain",
            "26.3, minecraft:item/iron_chain"
    })
    @DisplayName("A later vanilla move applies only on hosts that made it")
    void versionedMovesFollowTheHost(String target, String expected) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/models/item/angled_chain.json",
                "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"minecraft:item/chain\"}}"));

        run(tree, context("1.18.2", target));

        assertEquals(expected, textures(tree, "assets/oldmod/models/item/angled_chain.json")
                .get("layer0").getAsString());
    }

    @Test
    @DisplayName("A 1.16.5 model's grass path texture becomes dirt path, the 1.17 name")
    void grassPathBecomesDirtPath() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(ORE,
                "{\"textures\":{\"top\":\"minecraft:block/grass_path_top\"}}"));

        assertEquals(1, run(tree, context("1.16.5", "1.21.1")));
        assertEquals("minecraft:block/dirt_path_top", textures(tree, ORE).get("top").getAsString());
    }

    @ParameterizedTest(name = "1.13 sign references on {0}")
    @CsvSource({
            "1.21.1, minecraft:entity/signs/oak, minecraft:entity/signs/hanging/oak, minecraft:block/quartz_pillar",
            "26.1, minecraft:entity/signs/oak, minecraft:entity/signs/hanging/oak, minecraft:block/quartz_pillar",
            "26.3, minecraft:entity/sign, minecraft:entity/signs/hanging/oak, minecraft:block/quartz_pillar_side"
    })
    @DisplayName("Sign sheet references stop short of the 26.2 block sprites, which are new art in another layout")
    void signReferencesStopAtTheRedraw(String target, String sign, String hanging, String pillar) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(ORE,
                "{\"textures\":{\"sign\":\"minecraft:entity/sign\",\"hanging\":\"minecraft:entity/signs/hanging/oak\","
                        + "\"pillar\":\"minecraft:block/quartz_pillar\"}}"));

        run(tree, context("1.13", target));

        JsonObject textures = textures(tree, ORE);
        assertEquals(sign, textures.get("sign").getAsString());
        assertEquals(hanging, textures.get("hanging").getAsString());
        assertEquals(pillar, textures.get("pillar").getAsString(), "26.2 moved the pillar without redrawing it");
    }

    @Test
    @DisplayName("A 1.21.1 mod on 1.21.1 and a mod of unknown age are left alone")
    void sameFormatOrUnknownSourceUnchanged() {
        assertFalse(converter.appliesTo(context("1.21.1", "1.21.1")));
        assertFalse(converter.appliesTo(context(null, "26.3")));
    }

    @Test
    @DisplayName("A vanilla-namespaced texture the jar ships itself keeps its path")
    void jarShippedTextureKept() throws IOException {
        Map<String, byte[]> shipped = entries(
                "assets/minecraft/textures/blocks/oldmod_marble.png", "png",
                ORE, "{\"textures\":{\"all\":\"blocks/oldmod_marble\"}}");
        MapResourceTree tree = new MapResourceTree(shipped);

        assertEquals(0, run(tree, context("1.12.2", "26.3")));
        assertArrayEquals(shipped.get(ORE), tree.read(ORE));
    }

    @Test
    @DisplayName("The 26.1 sprite object keeps its form and its other fields")
    void spriteObjectForm() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(ORE,
                "{\"textures\":{\"all\":{\"sprite\":\"minecraft:item/chain\",\"force_translucent\":true}}}"));

        run(tree, context("1.20.1", "26.3"));

        JsonObject all = textures(tree, ORE).getAsJsonObject("all");
        assertEquals("minecraft:item/iron_chain", all.get("sprite").getAsString());
        assertTrue(all.get("force_translucent").getAsBoolean());
    }

    @Test
    @DisplayName("OBJ material lines are repointed and every other byte of the file is kept")
    void materialFiles() throws IOException {
        String material = "# exported\r\nnewmtl statue\r\nmap_Kd minecraft:blocks/stone\r\n"
                + "map_Kd oldmod:model/statue\r\nKd 1.0 1.0 1.0\r\n";
        MapResourceTree tree = new MapResourceTree(Map.of(
                "assets/oldmod/models/block/statue.mtl", material.getBytes(StandardCharsets.ISO_8859_1)));

        assertEquals(1, run(tree, context("1.12.2", "26.3")));
        assertEquals(material.replace("minecraft:blocks/stone", "minecraft:block/stone"),
                new String(tree.read("assets/oldmod/models/block/statue.mtl"), StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("A second run changes nothing")
    void idempotent() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(ORE,
                "{\"textures\":{\"a\":\"blocks/grass_top\",\"b\":\"minecraft:entity/llama/llama_brown\","
                        + "\"c\":\"items/chain\"}}"));
        MigrationContext context = context("1.12.2", "26.3");
        run(tree, context);
        Map<String, String> once = gameResourceDigests(tree.entries());

        assertEquals(0, run(tree, context));
        assertEquals(once, gameResourceDigests(tree.entries()));
        assertEquals("minecraft:entity/llama/llama_brown", textures(tree, ORE).get("b").getAsString(),
                "1.13 renamed it to brown and 26.1 back, so a 1.12 reference already names the 26.x file");
    }
}
