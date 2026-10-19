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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyModelParentMigratorTest {

    private final LegacyModelParentMigrator migrator = new LegacyModelParentMigrator();

    private static MigrationContext context(String source, String target) {
        return MigrationContext.builder(target).sourceMcVersion(source)
                .declaredNamespaces(List.of("oldmod")).build().forRun(List.of("oldmod"));
    }

    private static JsonObject model(MapResourceTree tree, String name) throws IOException {
        return JsonParser.parseString(text(tree.read(name))).getAsJsonObject();
    }

    private int run(MapResourceTree tree, MigrationContext context) throws IOException {
        return migrator.appliesTo(context) ? migrator.apply(tree, context) : 0;
    }

    @ParameterizedTest(name = "1.12.2 slab on {0}")
    @CsvSource({"1.21.1", "26.3"})
    @DisplayName("A 1.12 slab inherits the slab template that replaced half_slab")
    void halfSlabBecomesSlab(String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/models/block/ruby_slab.json",
                "{\"parent\":\"block/half_slab\",\"textures\":{\"bottom\":\"oldmod:blocks/ruby\","
                        + "\"top\":\"oldmod:blocks/ruby\",\"side\":\"oldmod:blocks/ruby\"}}",
                "assets/oldmod/models/block/ruby_slab_top.json",
                "{\"parent\":\"minecraft:block/upper_slab\",\"textures\":{\"bottom\":\"oldmod:blocks/ruby\"}}"));

        assertEquals(2, run(tree, context("1.12.2", target)));

        JsonObject slab = model(tree, "assets/oldmod/models/block/ruby_slab.json");
        assertEquals("block/slab", slab.get("parent").getAsString(), "written without a namespace, as shipped");
        assertEquals("oldmod:blocks/ruby", slab.getAsJsonObject("textures").get("top").getAsString(),
                "the slots kept their names, so the textures stay as they are");
        assertEquals("minecraft:block/slab_top",
                model(tree, "assets/oldmod/models/block/ruby_slab_top.json").get("parent").getAsString());
    }

    @Test
    @DisplayName("A 1.18.2 door gets the left and right templates 1.19 split it into")
    void doorTemplatesSplitIn119() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/models/block/door_bottom.json",
                "{\"parent\":\"minecraft:block/door_bottom\",\"textures\":{\"bottom\":\"oldmod:block/door\"}}",
                "assets/oldmod/models/block/door_bottom_hinge.json",
                "{\"parent\":\"minecraft:block/door_bottom_rh\",\"textures\":{\"bottom\":\"oldmod:block/door\"}}"));

        assertEquals(2, run(tree, context("1.18.2", "1.21.1")));

        assertEquals("minecraft:block/door_bottom_left",
                model(tree, "assets/oldmod/models/block/door_bottom.json").get("parent").getAsString());
        assertEquals("minecraft:block/door_bottom_right",
                model(tree, "assets/oldmod/models/block/door_bottom_hinge.json").get("parent").getAsString());
    }

    @ParameterizedTest(name = "block/grass from {0} on {1} is {2}")
    @CsvSource({
            "1.12.2, 26.3, block/grass_block",
            "1.16.5, 26.3, block/short_grass",
            "1.16.5, 1.20.1, block/grass"
    })
    @DisplayName("block/grass meant the grass block in 1.12 and the plant until 1.20.3, so each age gets its own")
    void grassMeansWhatTheModMeant(String source, String target, String expected) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/models/block/mossy_grass.json", "{\"parent\":\"block/grass\"}"));

        run(tree, context(source, target));

        assertEquals(expected, model(tree, "assets/oldmod/models/block/mossy_grass.json").get("parent").getAsString());
    }

    @Test
    @DisplayName("On 26.3 farmland moves to the indented template, which reads bottom and side instead of dirt")
    void farmlandTemplateOn263() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/models/block/rich_farmland.json",
                "{\"parent\":\"minecraft:block/template_farmland\","
                        + "\"textures\":{\"dirt\":\"oldmod:block/rich_dirt\",\"top\":\"oldmod:block/rich_top\"}}"));

        assertEquals(1, run(tree, context("1.21.1", "26.3")));

        JsonObject farmland = model(tree, "assets/oldmod/models/block/rich_farmland.json");
        assertEquals("minecraft:block/template_cube_bottom_top_indented", farmland.get("parent").getAsString());
        JsonObject textures = farmland.getAsJsonObject("textures");
        assertEquals("#dirt", textures.get("bottom").getAsString());
        assertEquals("#dirt", textures.get("side").getAsString());
        assertEquals("oldmod:block/rich_dirt", textures.get("dirt").getAsString(), "the mod's own slot stays");
    }

    @Test
    @DisplayName("26.2 still ships template_farmland, so a 26.2 host keeps the parent")
    void farmlandUnchangedOn262() throws IOException {
        Map<String, byte[]> shipped = entries(
                "assets/oldmod/models/block/rich_farmland.json",
                "{\"parent\":\"minecraft:block/template_farmland\",\"textures\":{\"dirt\":\"oldmod:block/d\"}}");
        MapResourceTree tree = new MapResourceTree(shipped);

        assertEquals(0, run(tree, context("1.21.1", "26.2")));
        assertArrayEquals(shipped.get("assets/oldmod/models/block/rich_farmland.json"),
                tree.read("assets/oldmod/models/block/rich_farmland.json"));
    }

    @ParameterizedTest(name = "{1} from {0} on 26.3")
    @CsvSource({
            "1.12.2, block/farmland",
            "1.20.1, minecraft:block/farmland",
            "26.2, minecraft:block/farmland_moist"
    })
    @DisplayName("26.3 farmland reads bottom and side, so a model that sets its own dirt passes it on")
    void keptFarmlandReadsTheModsDirtOn263(String source, String parent) throws IOException {
        String name = "assets/oldmod/models/block/rich_farmland.json";
        MapResourceTree tree = new MapResourceTree(entries(name,
                "{\"parent\":\"" + parent + "\","
                        + "\"textures\":{\"dirt\":\"oldmod:block/rich_dirt\",\"top\":\"oldmod:block/rich_top\"}}"));
        MigrationContext context = context(source, "26.3");

        assertEquals(1, run(tree, context));

        JsonObject farmland = model(tree, name);
        assertEquals(parent, farmland.get("parent").getAsString(), "26.3 still ships the model, so the parent stays");
        JsonObject textures = farmland.getAsJsonObject("textures");
        assertEquals("#dirt", textures.get("bottom").getAsString(), "vanilla's own bottom would be plain dirt");
        assertEquals("#dirt", textures.get("side").getAsString());
        assertEquals("oldmod:block/rich_dirt", textures.get("dirt").getAsString());
        assertEquals(0, run(tree, context), "a second run changes nothing");
    }

    @Test
    @DisplayName("The farmland slots go to the model that sets dirt, not to a base model that only names farmland")
    void farmlandSlotsGoWhereDirtIsSet() throws IOException {
        String base = "assets/oldmod/models/block/farm_base.json";
        String wet = "assets/oldmod/models/block/farm_wet.json";
        Map<String, byte[]> shipped = entries(
                base, "{\"parent\":\"block/farmland\"}",
                wet, "{\"parent\":\"oldmod:block/farm_base\","
                        + "\"textures\":{\"dirt\":\"oldmod:blocks/dirt\",\"top\":\"oldmod:blocks/wet\"}}");
        MapResourceTree tree = new MapResourceTree(shipped);

        assertEquals(1, run(tree, context("1.12.2", "26.3")));

        assertArrayEquals(shipped.get(base), tree.read(base), "#dirt would not resolve from the base model alone");
        JsonObject textures = model(tree, wet).getAsJsonObject("textures");
        assertEquals("#dirt", textures.get("bottom").getAsString());
        assertEquals("#dirt", textures.get("side").getAsString());
    }

    @Test
    @DisplayName("Farmland models stay as shipped on 26.2, when they use vanilla's dirt, or when they draw their own elements")
    void farmlandLeftAloneWhereTheOldSlotStillWorks() throws IOException {
        String ownDirt = "assets/oldmod/models/block/own_dirt.json";
        String vanillaDirt = "assets/oldmod/models/block/vanilla_dirt.json";
        String ownShape = "assets/oldmod/models/block/own_shape.json";
        Map<String, byte[]> shipped = entries(
                ownDirt, "{\"parent\":\"minecraft:block/farmland\",\"textures\":{\"dirt\":\"oldmod:block/d\"}}",
                vanillaDirt, "{\"parent\":\"minecraft:block/farmland\",\"textures\":{\"top\":\"oldmod:block/t\"}}",
                "assets/oldmod/models/block/own_shape_base.json",
                "{\"parent\":\"block/farmland\",\"elements\":[{\"from\":[0,0,0],\"to\":[16,8,16],"
                        + "\"faces\":{\"up\":{\"texture\":\"#dirt\"}}}]}",
                ownShape, "{\"parent\":\"oldmod:block/own_shape_base\",\"textures\":{\"dirt\":\"oldmod:block/d\"}}");

        MapResourceTree on262 = new MapResourceTree(shipped);
        assertEquals(0, run(on262, context("1.20.1", "26.2")), "26.2 farmland still reads dirt");
        assertEquals(gameResourceDigests(shipped), gameResourceDigests(on262.entries()));

        MapResourceTree on263 = new MapResourceTree(shipped);
        assertEquals(1, run(on263, context("1.20.1", "26.3")));
        assertArrayEquals(shipped.get(vanillaDirt), on263.read(vanillaDirt),
                "26.3 farmland sets its own bottom and side to vanilla dirt, which is what this model drew");
        assertArrayEquals(shipped.get(ownShape), on263.read(ownShape), "its own elements still read dirt");
        assertFalse(migrator.appliesTo(context("26.3", "26.3")), "a 26.3 model already uses the new slots");
    }

    @Test
    @DisplayName("A jar that ships minecraft:block/half_slab itself keeps using its own copy")
    void modShippedTemplateWins() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/minecraft/models/block/half_slab.json", "{\"elements\":[]}",
                "assets/oldmod/models/block/ruby_slab.json", "{\"parent\":\"block/half_slab\"}"));

        assertEquals(0, run(tree, context("1.12.2", "26.3")));
        assertEquals("block/half_slab", model(tree, "assets/oldmod/models/block/ruby_slab.json")
                .get("parent").getAsString());
    }

    @Test
    @DisplayName("Parents the host still has, the mod's own parents, and models of unknown age are left alone")
    void leavesOtherParentsAlone() throws IOException {
        Map<String, byte[]> shipped = entries(
                "assets/oldmod/models/block/ore.json", "{\"parent\":\"block/cube_all\"}",
                "assets/oldmod/models/block/pillar.json", "{\"parent\":\"oldmod:block/half_slab\"}",
                "assets/minecraft/models/block/glass.json", "{\"parent\":\"block/pane_post\"}");
        MapResourceTree tree = new MapResourceTree(shipped);

        assertEquals(0, run(tree, context("1.12.2", "26.3")), "vanilla's namespace is never edited");
        assertFalse(migrator.appliesTo(context(null, "26.3")), "an unknown source is not taken as old");
        assertFalse(migrator.appliesTo(context("26.3", "26.3")));
        assertEquals(gameResourceDigests(shipped), gameResourceDigests(tree.entries()));
    }

    @Test
    @DisplayName("A second run changes nothing")
    void idempotent() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/models/block/wall.json", "{\"parent\":\"block/wall_post\",\"textures\":{\"wall\":\"x\"}}",
                "assets/oldmod/models/block/farm.json", "{\"parent\":\"block/template_farmland\"}"));
        MigrationContext context = context("1.12.2", "26.3");
        assertEquals(2, run(tree, context));
        Map<String, String> once = gameResourceDigests(tree.entries());

        assertEquals(0, run(tree, context));
        assertEquals(once, gameResourceDigests(tree.entries()));
    }

    @Test
    @DisplayName("The tables load, and no two removed templates fold into the same replacement")
    void tableLoads() {
        List<LegacyModelParentMigrator.Rename> renames = LegacyModelParentMigrator.renames();
        assertFalse(renames.isEmpty());
        Set<String> destinations = new HashSet<>();
        for (LegacyModelParentMigrator.Rename rename : renames) {
            assertTrue(destinations.add(rename.newParent()), rename.newParent() + " is a destination once");
            assertNotEquals(rename.oldParent(), rename.newParent());
        }
        for (LegacyModelParentMigrator.SlotRename kept : LegacyModelParentMigrator.slotRenames()) {
            assertFalse(kept.addedSlots().isEmpty(), kept.model() + " renames at least one slot");
            assertTrue(renames.stream().noneMatch(rename -> rename.oldParent().equals(kept.model())
                            && rename.removedIn().equals(kept.changedIn())),
                    kept.model() + " cannot be both removed and kept in " + kept.changedIn());
        }
    }
}
