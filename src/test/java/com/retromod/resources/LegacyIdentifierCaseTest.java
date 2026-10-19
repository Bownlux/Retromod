/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.12 lowercased every identifier it parsed, so its mods wrote ids in any case. 1.13 and newer
 * reject an uppercase id, so the converter lowercases ids where each file reads them, and copies a
 * mixed-case texture, model, blockstate or sound to its lowercase name.
 */
class LegacyIdentifierCaseTest {

    private static final LegacyIdentifierCase CONVERTER = new LegacyIdentifierCase();

    private static MigrationContext context(String source, String target, MapResourceTree tree)
            throws IOException {
        return MigrationContext.builder(target).sourceMcVersion(source)
                .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(List.of("oldmod"))
                .build().forRun(ModResourceMigrations.rootNamespaces(tree.names()));
    }

    private static int run(MapResourceTree tree, String target) throws IOException {
        MigrationContext context = context("1.12.2", target, tree);
        assertTrue(CONVERTER.appliesTo(context), "a 1.12 mod on " + target);
        return CONVERTER.apply(tree, context);
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"1.13", "1.21.1", "26.3"})
    @DisplayName("A recipe result written in mixed case names the lowercase item")
    void recipeResultIsLowercased(String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/recipes/compass.json", "{\"type\":\"forge:ore_shaped\",\"pattern\":[\"SC\"],"
                        + "\"key\":{\"S\":{\"type\":\"forge:ore_dict\",\"ore\":\"treeSapling\"},"
                        + "\"C\":{\"item\":\"minecraft:compass\"}},\"result\":{\"item\":\"oldmod:NaturesCompass\"}}"));

        assertEquals(1, run(tree, target));

        String recipe = text(tree.read("assets/oldmod/recipes/compass.json"));
        assertTrue(recipe.contains("\"oldmod:naturescompass\""), recipe);
        assertTrue(recipe.contains("\"treeSapling\""), "ore dictionary names are matched by case");
        assertTrue(recipe.contains("\"SC\""), "the pattern is free text");
    }

    @Test
    @DisplayName("Model textures are lowercased, but texture variables keep their case")
    void modelTexturesAreLowercased() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/models/item/card.json", "{\"parent\":\"oldmod:item/Card_Base\","
                        + "\"textures\":{\"Front\":\"oldmod:items/models/card/Card_front\",\"layer0\":\"#Front\"},"
                        + "\"overrides\":[{\"predicate\":{\"open\":1},\"model\":\"oldmod:item/Card_Open\"}]}"));

        run(tree, "26.3");

        String model = text(tree.read("assets/oldmod/models/item/card.json"));
        assertTrue(model.contains("\"oldmod:items/models/card/card_front\""), model);
        assertTrue(model.contains("\"oldmod:item/card_base\""), model);
        assertTrue(model.contains("\"oldmod:item/card_open\""), model);
        assertTrue(model.contains("\"Front\""), "a texture variable's name is case-sensitive");
        assertTrue(model.contains("\"#Front\""), model);
    }

    @Test
    @DisplayName("Blockstates lowercase models and textures at any depth, never variant names")
    void blockstatesAreLowercased() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/lamp.json", "{\"forge_marker\":1,"
                        + "\"defaults\":{\"model\":\"oldmod:Lamp_Base\",\"textures\":{\"all\":\"oldmod:blocks/Lamp\"}},"
                        + "\"variants\":{\"Lit\":{\"true\":{\"model\":\"oldmod:Lamp_On\"},\"false\":{}},"
                        + "\"inventory\":[{}]}}"));

        run(tree, "1.21.1");

        String blockstate = text(tree.read("assets/oldmod/blockstates/lamp.json"));
        assertTrue(blockstate.contains("\"oldmod:lamp_base\""), blockstate);
        assertTrue(blockstate.contains("\"oldmod:blocks/lamp\""), blockstate);
        assertTrue(blockstate.contains("\"oldmod:lamp_on\""), blockstate);
        assertTrue(blockstate.contains("\"Lit\""), "variant and property names are left to their own converter");
    }

    @Test
    @DisplayName("Sound event names and files are lowercased; subtitles are translation keys and stay")
    void soundsAreLowercased() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/sounds.json", "{\"mob.Roar\":{\"category\":\"hostile\","
                        + "\"subtitle\":\"oldmod.subtitle.Roar\",\"sounds\":[\"oldmod:mob/Roar1\","
                        + "{\"name\":\"oldmod:mob/Roar2\",\"stream\":true}]},\"quiet\":{\"sounds\":[\"oldmod:quiet\"]}}"));

        run(tree, "26.3");

        String sounds = text(tree.read("assets/oldmod/sounds.json"));
        assertTrue(sounds.contains("\"mob.roar\""), sounds);
        assertTrue(sounds.contains("\"oldmod:mob/roar1\""), sounds);
        assertTrue(sounds.contains("\"oldmod:mob/roar2\""), sounds);
        assertTrue(sounds.contains("\"oldmod.subtitle.Roar\""), sounds);
        assertTrue(sounds.indexOf("mob.roar") < sounds.indexOf("quiet"), "events keep their order");
    }

    @Test
    @DisplayName("Loot entry names are lowercased; pool and modifier names are free text")
    void lootTableNamesAreLowercased() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/loot_tables/chests/vault.json", "{\"pools\":[{\"name\":\"Main\",\"rolls\":1,"
                        + "\"entries\":[{\"type\":\"item\",\"name\":\"oldmod:Ruby\",\"functions\":["
                        + "{\"function\":\"set_attributes\",\"modifiers\":[{\"name\":\"Damage Boost\","
                        + "\"attribute\":\"generic.attackDamage\",\"amount\":1}]}]},"
                        + "{\"type\":\"loot_table\",\"name\":\"oldmod:chests/Inner\"}]}]}"));

        run(tree, "26.3");

        String table = text(tree.read("assets/oldmod/loot_tables/chests/vault.json"));
        assertTrue(table.contains("\"oldmod:ruby\""), table);
        assertTrue(table.contains("\"oldmod:chests/inner\""), table);
        assertTrue(table.contains("\"Main\""), "a pool's name is looked up by its exact spelling");
        assertTrue(table.contains("\"Damage Boost\""), table);
        assertTrue(table.contains("\"generic.attackDamage\""), "a 1.12 attribute name is not an id");
    }

    @Test
    @DisplayName("Advancements lowercase parents, icons, criteria items and rewards, not their text")
    void advancementsAreLowercased() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/advancements/ruby.json", "{\"parent\":\"oldmod:Root\","
                        + "\"display\":{\"icon\":{\"item\":\"oldmod:Ruby\"},\"title\":{\"text\":\"Shiny Ruby\"}},"
                        + "\"criteria\":{\"got\":{\"trigger\":\"minecraft:inventory_changed\","
                        + "\"conditions\":{\"items\":[{\"item\":\"oldmod:Ruby\",\"nbt\":\"{Name:\\\"Ruby\\\"}\"}]}}},"
                        + "\"rewards\":{\"recipes\":[\"oldmod:Ruby_Block\"]}}"));

        run(tree, "1.21.1");

        String advancement = text(tree.read("assets/oldmod/advancements/ruby.json"));
        assertTrue(advancement.contains("\"oldmod:root\""), advancement);
        assertFalse(advancement.contains("oldmod:Ruby\""), advancement);
        assertTrue(advancement.contains("\"oldmod:ruby_block\""), advancement);
        assertTrue(advancement.contains("Shiny Ruby"), "display text is not an id");
        assertTrue(advancement.contains("Name:\\\"Ruby\\\""), "NBT is not an id");
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"1.13", "1.21.1", "26.3"})
    @DisplayName("A criterion's item predicate has its item, potion and enchantment ids lowercased")
    void itemPredicatesAreLowercased(String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/advancements/frost.json", "{\"criteria\":{"
                        + "\"eat\":{\"trigger\":\"consume_item\",\"conditions\":{\"item\":{\"item\":\"oldmod:RubyApple\"}}},"
                        + "\"drink\":{\"trigger\":\"consume_item\",\"conditions\":{\"item\":"
                        + "{\"item\":\"minecraft:potion\",\"potion\":\"oldmod:Frost\"}}},"
                        + "\"enchant\":{\"trigger\":\"enchanted_item\",\"conditions\":{\"item\":"
                        + "{\"enchantments\":[{\"enchantment\":\"oldmod:FrostTouch\",\"levels\":{\"min\":1}}]}}},"
                        + "\"hold\":{\"trigger\":\"inventory_changed\",\"conditions\":{\"items\":"
                        + "[{\"potion\":\"oldmod:Frost\",\"nbt\":\"{Potion:\\\"Frost\\\"}\"}]}}}}"));

        assertEquals(1, run(tree, target));

        JsonObject criteria = JsonParser.parseString(text(tree.read("assets/oldmod/advancements/frost.json")))
                .getAsJsonObject().getAsJsonObject("criteria");
        assertEquals("oldmod:rubyapple", predicate(criteria, "eat").get("item").getAsString());
        assertEquals("oldmod:frost", predicate(criteria, "drink").get("potion").getAsString());
        assertEquals("oldmod:frosttouch", predicate(criteria, "enchant").getAsJsonArray("enchantments")
                .get(0).getAsJsonObject().get("enchantment").getAsString());
        JsonObject held = criteria.getAsJsonObject("hold").getAsJsonObject("conditions")
                .getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("oldmod:frost", held.get("potion").getAsString());
        assertEquals("{Potion:\"Frost\"}", held.get("nbt").getAsString(), "NBT is not an id");
    }

    private static JsonObject predicate(JsonObject criteria, String criterion) {
        return criteria.getAsJsonObject(criterion).getAsJsonObject("conditions").getAsJsonObject("item");
    }

    @Test
    @DisplayName("Forge recipe constants are matched by name, so their files are left alone")
    void forgeRecipeConstantsStay() throws IOException {
        String constants = "[{\"name\":\"INGOTIRON\",\"ingredient\":{\"item\":\"minecraft:Iron_Ingot\"}}]";
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/recipes/_constants.json", constants));

        assertEquals(0, run(tree, "26.3"));
        assertEquals(constants, text(tree.read("assets/oldmod/recipes/_constants.json")));
    }

    @Test
    @DisplayName("A mixed-case texture and its animation are copied together to the lowercase name")
    void textureCopiedWithItsAnimation() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/textures/fx/laserFlash2x2.png", "frames",
                "assets/oldmod/textures/fx/laserFlash2x2.png.mcmeta", "{\"animation\":{}}",
                "assets/oldmod/sounds/mob/Roar.ogg", "ogg"));

        assertEquals(3, run(tree, "26.3"));

        assertArrayEquals(bytes("frames"), tree.read("assets/oldmod/textures/fx/laserflash2x2.png"));
        assertArrayEquals(bytes("{\"animation\":{}}"),
                tree.read("assets/oldmod/textures/fx/laserflash2x2.png.mcmeta"));
        assertArrayEquals(bytes("ogg"), tree.read("assets/oldmod/sounds/mob/roar.ogg"));
        assertTrue(tree.exists("assets/oldmod/textures/fx/laserFlash2x2.png"),
                "mod code can still read the original by its exact name");
    }

    @Test
    @DisplayName("Names that collide, are taken, or stay invalid are left as they are")
    void collisionsAndInvalidNamesStay() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/textures/blocks/Ore.png", "first",
                "assets/oldmod/textures/blocks/ORE.png", "second",
                "assets/oldmod/textures/blocks/Gem.png", "mixed",
                "assets/oldmod/textures/blocks/gem.png", "lowercase",
                "assets/oldmod/textures/blocks/Old Gem.png", "space",
                "assets/oldmod/defaultConfigs/Castle.properties", "read by the mod itself",
                "assets/minecraft/textures/blocks/Stone.png", "vanilla override"));

        assertEquals(0, run(tree, "26.3"));

        assertFalse(tree.exists("assets/oldmod/textures/blocks/ore.png"), "two files claim it");
        assertArrayEquals(bytes("lowercase"), tree.read("assets/oldmod/textures/blocks/gem.png"));
        assertFalse(tree.exists("assets/oldmod/textures/blocks/old gem.png"));
        assertFalse(tree.exists("assets/oldmod/defaultconfigs/castle.properties"),
                "only kinds the game loads by id are copied");
        assertFalse(tree.exists("assets/minecraft/textures/blocks/stone.png"),
                "a copy in minecraft would shadow vanilla's texture");
    }

    @Test
    @DisplayName("On disk the lowercase name always exists, and the original stays where the disk holds both")
    void directoryTreeGetsTheLowercaseName(@TempDir Path directory) throws IOException {
        writeDirectory(directory, entries("assets/oldmod/textures/items/Card.png", "card"));
        boolean caseSensitive = !Files.exists(directory.resolve("assets/oldmod/textures/items/CARD.png"));
        DirectoryResourceTree tree = DirectoryResourceTree.open(directory);
        MigrationContext context = context("1.12.2", "26.3", new MapResourceTree(readDirectory(directory)));

        assertEquals(1, CONVERTER.apply(tree, context));

        Path folder = directory.resolve("assets/oldmod/textures/items");
        assertEquals(caseSensitive ? List.of("Card.png", "card.png") : List.of("card.png"), listing(folder),
                "a disk that ignores case cannot hold both names, so the models' name wins");
        assertEquals("card", Files.readString(folder.resolve("card.png")));
    }

    @Test
    @DisplayName("A standalone pack renames its mixed-case files, since no code reads them by name")
    void standalonePackRenames() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/textures/blocks/Ruby.png", "ruby",
                "assets/oldmod/textures/blocks/Ruby.png.mcmeta", "{\"animation\":{}}"));

        assertEquals(2, CONVERTER.apply(tree, packContext(tree)));

        assertEquals(List.of("assets/oldmod/textures/blocks/ruby.png",
                "assets/oldmod/textures/blocks/ruby.png.mcmeta"), List.copyOf(tree.names()));
        assertArrayEquals(bytes("ruby"), tree.read("assets/oldmod/textures/blocks/ruby.png"));
    }

    @Test
    @DisplayName("A pack keeps a file's name when other files keep its mixed-case folder")
    void standalonePackKeepsAFolderStillInUse(@TempDir Path directory) throws IOException {
        writeDirectory(directory, entries(
                "assets/oldmod/textures/blocks/Gems/Ruby.png", "ruby",
                "assets/oldmod/textures/blocks/Gems/Old Gem.png", "space"));
        DirectoryResourceTree tree = DirectoryResourceTree.open(directory);

        assertEquals(0, CONVERTER.apply(tree, packContext(tree)));

        assertEquals(List.of("Gems"), listing(directory.resolve("assets/oldmod/textures/blocks")),
                "one folder name, so the pack can still be written");
        assertTrue(tree.exists("assets/oldmod/textures/blocks/Gems/Ruby.png"));
    }

    @Test
    @DisplayName("A pack's emptied mixed-case folder is removed, so the pack holds one folder name")
    void standalonePackDropsTheEmptiedFolder(@TempDir Path directory) throws IOException {
        writeDirectory(directory, entries("assets/oldmod/textures/items/Tools/Pick.png", "pick"));
        DirectoryResourceTree tree = DirectoryResourceTree.open(directory);

        assertEquals(1, CONVERTER.apply(tree, packContext(tree)));

        assertEquals(List.of("tools"), listing(directory.resolve("assets/oldmod/textures/items")));
        assertEquals(List.of("pick.png"), listing(directory.resolve("assets/oldmod/textures/items/tools")));
    }

    private static MigrationContext packContext(ResourceTree tree) throws IOException {
        return MigrationContext.builder("26.3").sourceMcVersion("1.12.2").standalonePack(true).build()
                .forRun(ModResourceMigrations.rootNamespaces(tree.names()));
    }

    private static List<String> listing(Path folder) throws IOException {
        try (var entries = Files.list(folder)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @DisplayName("Free text in language files is never touched")
    void languageFilesStay() throws IOException {
        String lang = "{\"item.oldmod.Ruby.name\":\"oldmod:Ruby\"}";
        MapResourceTree tree = new MapResourceTree(entries("assets/oldmod/lang/en_us.json", lang));

        assertEquals(0, run(tree, "26.3"));
        assertEquals(lang, text(tree.read("assets/oldmod/lang/en_us.json")));
    }

    @Test
    @DisplayName("A 1.13 mod and an older host are left alone, and a second run changes nothing")
    void gatedAndIdempotent() throws IOException {
        Map<String, byte[]> files = entries(
                "assets/oldmod/models/item/ruby.json", "{\"textures\":{\"layer0\":\"oldmod:items/Ruby\"}}",
                "assets/oldmod/textures/items/Ruby.png", "ruby");
        MapResourceTree tree = new MapResourceTree(files);

        assertFalse(CONVERTER.appliesTo(context("1.13", "26.3", tree)), "1.13 already wrote lowercase ids");
        assertFalse(CONVERTER.appliesTo(context("1.12.2", "1.12.2", tree)),
                "a 1.12 host still lowercases ids itself");
        assertFalse(CONVERTER.appliesTo(MigrationContext.builder("26.3").build()),
                "an unknown source is not taken as old");

        assertEquals(2, run(tree, "26.3"));
        Map<String, byte[]> once = Map.copyOf(tree.entries());
        assertEquals(0, run(tree, "26.3"));
        assertEquals(once.keySet(), tree.entries().keySet());
    }

    @Test
    @DisplayName("On 26.3 the whole pipeline copies a lowercased recipe to the folder the host reads")
    void pipelineCopiesTheLowercasedRecipe() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/recipes/ruby_block.json", "{\"type\":\"minecraft:crafting_shapeless\","
                        + "\"ingredients\":[{\"item\":\"oldmod:Ruby\"}],\"result\":{\"item\":\"oldmod:Ruby_Block\"}}"));

        ModResourceMigrations.standard().run(tree, MigrationContext.builder("26.3").sourceMcVersion("1.12.2")
                .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(List.of("oldmod")).build());

        String copy = text(tree.read("data/oldmod/recipe/ruby_block.json"));
        assertTrue(copy.contains("oldmod:ruby_block"), copy);
        assertFalse(copy.matches("(?s).*oldmod:[A-Za-z_]*[A-Z].*"), copy);
    }
}
