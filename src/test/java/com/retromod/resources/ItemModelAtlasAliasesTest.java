/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ItemModelAtlasAliasesTest {

    private static final String BLOCKS = ModTextureAtlasSources.BLOCKS_ATLAS;
    private static final String ITEMS = ModTextureAtlasSources.ITEMS_ATLAS;
    private static final Path CORPUS = Path.of(System.getProperty("user.home"),
            "Development/Minecraft/RetroMod/.retromod-work/survey/dl-forge1201");

    private final ItemModelAtlasAliases converter = new ItemModelAtlasAliases();

    private static MigrationContext context(String source, String target, MigrationContext.Loader loader,
            List<String> namespaces) {
        return MigrationContext.builder(target).sourceMcVersion(source).targetLoader(loader)
                .declaredNamespaces(namespaces).build().forRun(namespaces);
    }

    private int run(MapResourceTree tree, MigrationContext context) throws IOException {
        return converter.appliesTo(context) ? converter.apply(tree, context) : 0;
    }

    /** Each single source of one atlas file, as {@code sprite <- resource}, or the resource alone; other source types are skipped. */
    private static Set<String> singles(MapResourceTree tree, String atlas) throws IOException {
        Set<String> singles = new TreeSet<>();
        if (!tree.exists(atlas)) return singles;
        JsonObject definition = JsonParser.parseString(text(tree.read(atlas))).getAsJsonObject();
        for (JsonElement source : definition.getAsJsonArray("sources")) {
            JsonObject single = source.getAsJsonObject();
            if (!single.has("resource")) continue;
            String resource = single.get("resource").getAsString();
            singles.add(single.has("sprite") ? single.get("sprite").getAsString() + " <- " + resource : resource);
        }
        return singles;
    }

    private static JsonObject textures(MapResourceTree tree, String model) throws IOException {
        return JsonParser.parseString(text(tree.read(model))).getAsJsonObject().getAsJsonObject("textures");
    }

    @Test
    @DisplayName("An item model that mixes a block and an item texture draws the item one from a blocks atlas copy")
    void mixedModelUsesTheBlocksAtlas() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/stones/models/item/portstone.json", "{\"textures\":{\"1\":\"stones:item/warp_stone\","
                        + "\"texture\":\"stones:block/andesite\",\"particle\":\"stones:item/warp_stone\"},\"elements\":[]}"));

        assertEquals(2, run(tree, context("1.20.1", "26.2", MigrationContext.Loader.FORGE, List.of("stones"))),
                "the model and blocks.json");

        JsonObject textures = textures(tree, "assets/stones/models/item/portstone.json");
        assertEquals("stones:retromod/blocks_atlas/stones/item/warp_stone", textures.get("1").getAsString());
        assertEquals("stones:block/andesite", textures.get("texture").getAsString());
        assertEquals("stones:item/warp_stone", textures.get("particle").getAsString(), "no quad draws the particle");
        assertEquals(Set.of("stones:retromod/blocks_atlas/stones/item/warp_stone <- stones:item/warp_stone"),
                singles(tree, BLOCKS));
        assertFalse(tree.exists(ITEMS));
    }

    @Test
    @DisplayName("A slot a block model parent sets gets the copy in the item model, and the parent stays as it was")
    void inheritedSlotIsOverriddenInTheItemModel() throws IOException {
        String parent = "{\"textures\":{\"side\":\"stones:block/andesite\",\"gem\":\"stones:item/warp_stone\"}}";
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/stones/models/block/pillar.json", parent,
                "assets/stones/models/item/pillar.json", "{\"parent\":\"stones:block/pillar\"}"));

        run(tree, context("1.20.1", "26.3", MigrationContext.Loader.NEOFORGE, List.of("stones")));

        assertEquals("stones:retromod/blocks_atlas/stones/item/warp_stone",
                textures(tree, "assets/stones/models/item/pillar.json").get("gem").getAsString());
        assertEquals(parent, text(tree.read("assets/stones/models/block/pillar.json")), "the block model is unchanged");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "one atlas | {\"textures\":{\"layer0\":\"stones:item/gem\",\"layer1\":\"minecraft:item/stick\"}}",
            "an unknown atlas | {\"textures\":{\"a\":\"stones:custom/gem\",\"b\":\"stones:item/gem\"}}"})
    @DisplayName("A model whose textures share one atlas, or whose atlas Retromod cannot tell, is left alone")
    void untouchedModels(String what, String model) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries("assets/stones/models/item/gem.json", model));

        assertEquals(0, run(tree, context("1.20.1", "26.2", MigrationContext.Loader.FABRIC, List.of("stones"))), what);
        assertEquals(model, text(tree.read("assets/stones/models/item/gem.json")));
    }

    @Test
    @DisplayName("A Forge fluid container's still texture is listed in the items atlas under the item/ path Forge reads")
    void forgeFluidContainer() throws IOException {
        Map<String, byte[]> shipped = entries(
                "assets/core/models/item/xp_bucket.json",
                "{\"parent\":\"forge:item/bucket\",\"fluid\":\"core:xp_still\",\"loader\":\"forge:fluid_container\"}",
                "assets/core/textures/block/xp_still.png", "png");
        MapResourceTree forge = new MapResourceTree(shipped);
        MapResourceTree neoForge = new MapResourceTree(shipped);

        assertEquals(1, run(forge, context("1.20.1", "26.2", MigrationContext.Loader.FORGE, List.of("core"))));
        run(neoForge, context("1.20.1", "26.3", MigrationContext.Loader.NEOFORGE, List.of("core")));

        assertEquals(Set.of("core:item/xp_still <- core:block/xp_still"), singles(forge, ITEMS));
        assertFalse(neoForge.exists(ITEMS), "NeoForge's fluid container reads the blocks atlas itself");
    }

    @Test
    @DisplayName("Only mods built before 1.21.11 and translated for 1.21.11 or newer are changed")
    void versionGate() {
        List<String> namespaces = List.of("stones");
        assertTrue(converter.appliesTo(context("1.21.1", "1.21.11", MigrationContext.Loader.FABRIC, namespaces)));
        assertFalse(converter.appliesTo(context("1.21.1", "1.21.10", MigrationContext.Loader.FABRIC, namespaces)));
        assertFalse(converter.appliesTo(context("1.21.11", "26.2", MigrationContext.Loader.FORGE, namespaces)));
    }

    @Test
    @DisplayName("Waystones' portstone and Sophisticated Core's experience bucket each come out with one atlas")
    void realJars() throws IOException {
        MapResourceTree waystones = jar("waystones-forge-1.20.1-14.1.21.jar");
        MapResourceTree core = jar("sophisticatedcore-1.20.1-1.5.17.2435.jar");

        int waystonesChanged = run(waystones, context("1.20.1", "26.2", MigrationContext.Loader.FORGE, List.of("waystones")));
        int coreChanged = run(core, context("1.20.1", "26.2", MigrationContext.Loader.FORGE, List.of("sophisticatedcore")));

        JsonObject portstone = textures(waystones, "assets/waystones/models/item/portstone.json");
        assertEquals("waystones:retromod/blocks_atlas/waystones/item/warp_stone", portstone.get("1").getAsString());
        assertEquals("waystones:block/andesite_waystone", portstone.get("texture").getAsString());
        assertTrue(singles(waystones, BLOCKS).contains(
                "waystones:retromod/blocks_atlas/waystones/item/warp_stone <- waystones:item/warp_stone"));
        assertTrue(text(waystones.read(BLOCKS)).contains("waystone_overlays/"), "the jar's own directory source stays first");
        assertTrue(singles(core, ITEMS).contains("sophisticatedcore:item/xp_still <- sophisticatedcore:block/xp_still"));
        System.out.println("Waystones: " + waystonesChanged + " file(s), blocks atlas copies " + singles(waystones, BLOCKS)
                + "; Sophisticated Core: " + coreChanged + " file(s), items atlas copies " + singles(core, ITEMS));
    }

    /** The assets of one corpus jar, or a skipped test when the corpus is not on this machine. */
    private static MapResourceTree jar(String name) throws IOException {
        Path jar = CORPUS.resolve(name);
        Assumptions.assumeTrue(Files.isRegularFile(jar), "no " + name + " in the corpus");
        Map<String, byte[]> assets = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.isDirectory() && entry.getName().startsWith("assets/")) {
                    assets.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
                }
            }
        }
        return new MapResourceTree(new LinkedHashMap<>(assets));
    }
}
