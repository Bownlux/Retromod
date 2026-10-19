/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Old data sits in folders a newer game never reads: 1.12 kept it under {@code assets/}, and
 * everything before 1.21 used plural folder names. The layout step copies it to where the host
 * reads it, leaves the original in place, and never overwrites what the mod already ships there.
 */
class ModDataLayoutMigratorTest {

    private static final String RECIPE = "{\"type\":\"minecraft:crafting_shapeless\","
            + "\"ingredients\":[\"minecraft:stick\"],\"result\":{\"id\":\"mod:ruby\"}}";

    private static MigrationContext context(String source, String target) {
        return MigrationContext.builder(target).sourceMcVersion(source)
                .targetLoader(MigrationContext.Loader.NEOFORGE).build().forRun(List.of("mod"));
    }

    private static int migrate(ResourceTree tree, String source, String target) throws IOException {
        MigrationContext context = context(source, target);
        ModDataLayoutMigrator layout = new ModDataLayoutMigrator();
        return layout.appliesTo(context) ? layout.apply(tree, context) : 0;
    }

    @ParameterizedTest(name = "{0} -> {1} on {2}")
    @CsvSource({
            "recipes/ruby.json, recipe/ruby.json, 1.21",
            "loot_tables/blocks/ruby_ore.json, loot_table/blocks/ruby_ore.json, 26.3",
            "advancements/story/root.json, advancement/story/root.json, 1.21.1",
            "structures/house.nbt, structure/house.nbt, 26.3",
            "predicates/in_rain.json, predicate/in_rain.json, 1.21.11",
            "item_modifiers/add_name.json, item_modifier/add_name.json, 26.3",
            "functions/tick.mcfunction, function/tick.mcfunction, 1.21",
            "tags/blocks/mineable/pickaxe.json, tags/block/mineable/pickaxe.json, 26.3",
            "tags/items/gems.json, tags/item/gems.json, 1.21",
            "tags/entity_types/bosses.json, tags/entity_type/bosses.json, 26.3",
            "tags/fluids/oil.json, tags/fluid/oil.json, 1.21",
            "tags/game_events/loud.json, tags/game_event/loud.json, 1.21",
            "tags/functions/load.json, tags/function/load.json, 1.21"
    })
    @DisplayName("Each plural data folder is copied to its 1.21 singular name")
    void copiesPluralFoldersToSingular(String from, String to, String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries("data/mod/" + from, "{\"values\":[]}"));

        assertEquals(1, migrate(tree, "1.20.1", target));

        assertTrue(tree.exists("data/mod/" + to), "copied to data/mod/" + to);
        assertTrue(tree.exists("data/mod/" + from), "the original is kept");
    }

    @ParameterizedTest(name = "source {0}, target {1}")
    @CsvSource({"1.21.1, 26.3", "1.20.1, 1.20.4", "1.19.2, 1.20.1"})
    @DisplayName("Plural folders stay put unless the source predates 1.21 and the host is 1.21+")
    void pluralFoldersNeedBothVersionGates(String source, String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries("data/mod/recipes/ruby.json", RECIPE));

        assertEquals(0, migrate(tree, source, target));
        assertFalse(tree.exists("data/mod/recipe/ruby.json"));
    }

    @Test
    @DisplayName("An unknown source still gets the plural copy, which only fills a missing folder")
    void unknownSourceStillGetsSingularFolders() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "data/mod/recipes/ruby.json", RECIPE,
                "assets/mod/recipes/old.json", RECIPE));

        migrate(tree, null, "26.3");

        assertTrue(tree.exists("data/mod/recipe/ruby.json"));
        assertFalse(tree.exists("data/mod/recipe/old.json"),
                "assets-side data is copied only for a source known to predate 1.13");
    }

    @Test
    @DisplayName("An unknown source with any 1.21 folder in the jar keeps its plural leftovers inert")
    void unknownSourceWithSingularFoldersIsLeftAlone() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "data/mod/recipe/a.json", RECIPE,
                "data/mod/tags/blocks/stale.json", "{\"values\":[]}",
                "data/minecraft/loot_tables/blocks/stone.json", "{\"pools\":[]}"));

        assertEquals(0, migrate(tree, null, "1.21.11"));

        assertFalse(tree.exists("data/mod/tags/block/stale.json"),
                "a 1.21 jar never loaded its stale plural tags, so they stay unloaded");
        assertFalse(tree.exists("data/minecraft/loot_table/blocks/stone.json"));
    }

    @ParameterizedTest(name = "data/{0}/{1} on {3}")
    @CsvSource({
            "minecraft, advancements/story/root.json, advancement/story/root.json, 1.21.1",
            "minecraft, recipes/bread.json, recipe/bread.json, 1.21.11",
            "minecraft, loot_tables/chests/simple_dungeon.json, loot_table/chests/simple_dungeon.json, 26.2",
            "c, recipes/tool.json, recipe/tool.json, 1.21.1"
    })
    @DisplayName("In a shared namespace only tags are copied, so vanilla's own entries keep loading")
    void sharedNamespacesCopyOnlyTags(String namespace, String from, String to, String target)
            throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "data/" + namespace + "/" + from, "{\"icon\":{\"item\":\"minecraft:grass_block\"}}",
                "data/" + namespace + "/tags/blocks/mineable/pickaxe.json", "{\"values\":[\"mod:ore\"]}"));

        assertEquals(1, migrate(tree, "1.20.1", target), "only the tag is copied");

        assertFalse(tree.exists("data/" + namespace + "/" + to),
                "an old-shape copy would replace vanilla's working entry");
        assertTrue(tree.exists("data/" + namespace + "/tags/block/mineable/pickaxe.json"));
    }

    @Test
    @DisplayName("A kind the mod already ships in its singular folder is left alone")
    void singularFolderWins() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "data/mod/recipes/ruby.json", "{\"old\":true}",
                "data/mod/recipes/only_old.json", RECIPE,
                "data/mod/recipe/ruby.json", "{\"new\":true}",
                "data/mod/loot_tables/blocks/ore.json", "{}"));

        assertEquals(1, migrate(tree, "1.20.1", "26.3"), "only the loot table is copied");

        assertEquals("{\"new\":true}", text(tree.read("data/mod/recipe/ruby.json")));
        assertFalse(tree.exists("data/mod/recipe/only_old.json"),
                "a multi-version jar that ships recipe/ already serves 1.21");
        assertTrue(tree.exists("data/mod/loot_table/blocks/ore.json"));
    }

    @Test
    @DisplayName("1.12 assets-side data is copied to data/, singular on 1.21+ and plural before")
    void copiesAssetSideData() throws IOException {
        Map<String, byte[]> legacy = entries(
                "assets/mod/recipes/ruby.json", RECIPE,
                "assets/mod/recipes/_constants.json", "[]",
                "assets/mod/recipes/_factories.json", "{}",
                "assets/mod/loot_tables/chests/hut.json", "{}",
                "assets/mod/structures/hut.nbt", "nbt",
                "assets/mod/advancements/root.json", "{}",
                "assets/mod/textures/blocks/ruby.png", "png",
                "assets/minecraft/recipes/stick.json", RECIPE);

        MapResourceTree modern = new MapResourceTree(legacy);
        migrate(modern, "1.12.2", "26.3");
        assertTrue(modern.exists("data/mod/recipe/ruby.json"));
        assertTrue(modern.exists("data/mod/loot_table/chests/hut.json"));
        assertTrue(modern.exists("data/mod/structure/hut.nbt"));
        assertTrue(modern.exists("data/mod/advancement/root.json"));
        assertFalse(modern.exists("data/mod/recipe/_constants.json"), "Forge helpers stay behind");
        assertFalse(modern.exists("data/mod/recipe/_factories.json"));
        assertFalse(modern.exists("data/mod/textures/blocks/ruby.png"), "assets stay assets");
        assertFalse(modern.exists("data/minecraft/recipe/stick.json"),
                "1.12 never let a mod's assets/minecraft data replace vanilla's");
        assertEquals(RECIPE, text(modern.read("assets/mod/recipes/ruby.json")),
                "the original stays for mod code that reads it from the classpath");

        MapResourceTree older = new MapResourceTree(legacy);
        migrate(older, "1.12.2", "1.20.1");
        assertTrue(older.exists("data/mod/recipes/ruby.json"));
        assertFalse(older.exists("data/mod/recipe/ruby.json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.12.2", "1.10.2"})
    @DisplayName("Assets-side data needs a source before 1.13 and a host from 1.13")
    void assetSideCopyIsGated(String source) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries("assets/mod/recipes/ruby.json", RECIPE));

        migrate(tree, "1.13", "26.3");
        assertFalse(tree.exists("data/mod/recipe/ruby.json"), "a 1.13 source already reads data/");

        migrate(tree, source, "26.3");
        assertTrue(tree.exists("data/mod/recipe/ruby.json"));
    }

    @Test
    @DisplayName("A jar that ships both layouts keeps its own data-side copy")
    void dualLayoutKeepsDataSide() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/mod/recipes/planks.json", "{\"from\":\"assets\"}",
                "data/mod/recipes/planks.json", "{\"from\":\"data\"}"));

        migrate(tree, "1.12.2", "26.3");

        assertEquals("{\"from\":\"data\"}", text(tree.read("data/mod/recipe/planks.json")));
        assertEquals("{\"from\":\"data\"}", text(tree.read("data/mod/recipes/planks.json")));
    }

    @Test
    @DisplayName("Uppercase names are lowercased when that makes them valid; invalid names stay put")
    void lowercasesWhenValid() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/mod/recipes/hammock_lightBlue.json", RECIPE,
                "assets/mod/recipes/pie - Copy.json", RECIPE,
                "assets/mod/recipes/Twin.json", "{\"upper\":true}",
                "assets/mod/recipes/twin.json", "{\"lower\":true}"));

        migrate(tree, "1.12.2", "26.3");

        assertTrue(tree.exists("data/mod/recipe/hammock_lightblue.json"));
        assertFalse(tree.names().stream().anyMatch(name -> name.startsWith("data/")
                && name.toLowerCase(java.util.Locale.ROOT).contains("copy")),
                "a name that stays invalid is not copied");
        assertEquals("{\"lower\":true}", text(tree.read("data/mod/recipe/twin.json")),
                "a name valid as shipped wins over one that is only valid lowercased");
    }

    @Test
    @DisplayName("A nested data pack is migrated from the format its own pack.mcmeta declares")
    void nestedPackRootsUseTheirOwnFormat() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "extra_pack/pack.mcmeta", "{\"pack\":{\"pack_format\":15,\"description\":\"\"}}",
                "extra_pack/data/mod/recipes/a.json", RECIPE,
                "modern_pack/pack.mcmeta", "{\"pack\":{\"pack_format\":48,\"description\":\"\"}}",
                "modern_pack/data/mod/recipes/b.json", RECIPE,
                "no_pack/data/mod/recipes/c.json", RECIPE));

        migrate(tree, "1.21.1", "26.3");

        assertTrue(tree.exists("extra_pack/data/mod/recipe/a.json"),
                "a format 15 pack is a 1.20 pack, whatever the jar's own version");
        assertFalse(tree.exists("modern_pack/data/mod/recipe/b.json"),
                "a format 48 pack already uses the 1.21 names");
        assertFalse(tree.exists("no_pack/data/mod/recipe/c.json"),
                "a folder without pack.mcmeta is not a pack");
    }

    @Test
    @DisplayName("Running the layout step twice changes nothing the second time")
    void idempotent() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/mod/recipes/ruby.json", RECIPE,
                "data/mod/tags/blocks/mineable/pickaxe.json", "{\"values\":[\"mod:ruby_ore\"]}"));

        assertTrue(migrate(tree, "1.12.2", "26.3") > 0);
        Map<String, byte[]> first = Map.copyOf(tree.entries());

        assertEquals(0, migrate(tree, "1.12.2", "26.3"));
        assertEquals(gameResourceDigests(first), gameResourceDigests(tree.entries()));
    }

    @Test
    @DisplayName("Each copy is recorded for the load gate")
    void recordsRelocations() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries("assets/mod/loot_tables/a.json", "{}"));
        MigrationContext context = context("1.12.2", "26.3");

        new ModDataLayoutMigrator().apply(tree, context);

        assertEquals(List.of(new MigrationLedger.Relocation("assets/mod/loot_tables/a.json",
                "data/mod/loot_table/a.json", "loot_table", true)), context.ledger().relocations());
    }
}
