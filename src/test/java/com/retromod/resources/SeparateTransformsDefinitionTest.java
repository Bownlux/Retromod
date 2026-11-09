/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** A Forge separate_transforms item model becomes an item definition that selects its model by display context. */
class SeparateTransformsDefinitionTest {

    // Artifacts' umbrella: one model in the GUI and another held in hand and worn.
    private static final String UMBRELLA = "{\"loader\":\"forge:separate_transforms\",\"gui_light\":\"front\","
            + "\"base\":{\"parent\":\"oldmod:item/umbrella_gui\"},\"perspectives\":{"
            + "\"firstperson_righthand\":{\"parent\":\"oldmod:item/umbrella_held\"},"
            + "\"firstperson_lefthand\":{\"parent\":\"oldmod:item/umbrella_held\"},"
            + "\"thirdperson_righthand\":{\"parent\":\"oldmod:item/umbrella_held\"},"
            + "\"head\":{\"parent\":\"oldmod:item/umbrella_held\"},"
            + "\"fancy_hat\":{\"parent\":\"oldmod:item/umbrella_held\"}}}";
    private static final String FLAT = "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"oldmod:item/flat\"}}";

    private static MapResourceTree run(MigrationContext.Loader source, String target, Map<String, byte[]> files) throws IOException {
        MapResourceTree tree = new MapResourceTree(files);
        MigrationContext context = MigrationContext.builder(target).sourceMcVersion("1.20.1").sourceLoader(source)
                .targetLoader(source == MigrationContext.Loader.FABRIC ? MigrationContext.Loader.FABRIC : MigrationContext.Loader.FORGE)
                .declaredNamespaces(List.of("oldmod")).build();
        ModResourceMigrations.standard().run(tree, context);
        return tree;
    }

    private static Map<String, byte[]> umbrella(String model) {
        return entries("assets/oldmod/models/item/umbrella.json", model,
                "assets/oldmod/models/item/umbrella_gui.json", FLAT,
                "assets/oldmod/models/item/umbrella_held.json", FLAT);
    }

    private static JsonObject definition(MapResourceTree tree, String item) throws IOException {
        String name = "assets/oldmod/items/" + item + ".json";
        assertTrue(tree.exists(name), "a definition for " + item);
        return JsonParser.parseString(text(tree.read(name))).getAsJsonObject().getAsJsonObject("model");
    }

    private static JsonObject plain(String model) {
        JsonObject leaf = new JsonObject();
        leaf.addProperty("type", "minecraft:model");
        leaf.addProperty("model", model);
        return leaf;
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Each perspective's model is a case of a select on the display context, and the base model the fallback")
    void perspectivesBecomeASelect(String target) throws IOException {
        JsonObject model = definition(run(MigrationContext.Loader.FORGE, target, umbrella(UMBRELLA)), "umbrella");
        assertEquals("minecraft:select", model.get("type").getAsString());
        assertEquals("minecraft:display_context", model.get("property").getAsString());
        assertEquals(plain("oldmod:item/umbrella_gui"), model.get("fallback"));
        JsonArray cases = model.getAsJsonArray("cases");
        assertEquals(1, cases.size(), "the perspectives that share a model share a case");
        JsonObject held = cases.get(0).getAsJsonObject();
        assertEquals(plain("oldmod:item/umbrella_held"), held.get("model"));
        List<String> when = held.getAsJsonArray("when").asList().stream().map(element -> element.getAsString()).toList();
        assertEquals(List.of("firstperson_righthand", "firstperson_lefthand", "thirdperson_righthand", "head"), when,
                "a context the host does not know is left out, so the definition still loads");
    }

    @Test
    @DisplayName("An inline perspective model is written as a model file of its own, which the case names")
    void inlinePerspectivesGetTheirOwnFile() throws IOException {
        String inline = "{\"loader\":\"forge:separate_transforms\",\"base\":{\"parent\":\"oldmod:item/umbrella_gui\"},"
                + "\"perspectives\":{\"gui\":{\"parent\":\"oldmod:item/umbrella_held\",\"textures\":{\"layer0\":\"oldmod:item/tiny\"}}}}";
        MapResourceTree tree = run(MigrationContext.Loader.FORGE, "26.2", umbrella(inline));
        JsonObject model = definition(tree, "umbrella");
        String generated = "oldmod:item/retromod/separate_transforms/gui/umbrella";
        assertEquals(plain(generated), model.getAsJsonArray("cases").get(0).getAsJsonObject().get("model"));
        JsonObject file = JsonParser.parseString(text(tree.read("assets/oldmod/models/item/retromod/separate_transforms/gui/umbrella.json")))
                .getAsJsonObject();
        assertEquals("oldmod:item/umbrella_held", file.get("parent").getAsString());
        assertEquals("oldmod:item/tiny", file.getAsJsonObject("textures").get("layer0").getAsString());
        assertTrue(tree.exists("assets/oldmod/models/item/umbrella.json"), "the mod's own model stays");
    }

    @Test
    @DisplayName("Overrides on a separate_transforms model pick between selects, each with its own base")
    void overridesWrapTheSelect() throws IOException {
        String quiver = "{\"loader\":\"forge:separate_transforms\",\"base\":{\"parent\":\"oldmod:item/umbrella_held\"},"
                + "\"perspectives\":{\"gui\":{\"parent\":\"oldmod:item/umbrella_gui\"}},"
                + "\"overrides\":[{\"predicate\":{\"custom_model_data\":1},\"model\":\"oldmod:item/umbrella_dyed\"}]}";
        String dyed = "{\"loader\":\"forge:separate_transforms\",\"base\":{\"parent\":\"oldmod:item/umbrella_gui\"}}";
        Map<String, byte[]> files = new java.util.HashMap<>(umbrella(quiver));
        files.putAll(entries("assets/oldmod/models/item/umbrella_dyed.json", dyed));
        JsonObject model = definition(run(MigrationContext.Loader.FORGE, "26.2", files), "umbrella");
        assertEquals("minecraft:range_dispatch", model.get("type").getAsString());
        assertEquals("minecraft:select", model.getAsJsonObject("fallback").get("type").getAsString(), "no data: the select");
        JsonObject dyedNode = model.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("model");
        assertEquals(plain("oldmod:item/umbrella_gui"), dyedNode, "a separate_transforms with only a base is its base");
    }

    @Test
    @DisplayName("A perspective key spelled another way, which Forge 1.20.1 never read, makes no second case for one context")
    void contextKeysMatchExactly() throws IOException {
        String twice = "{\"loader\":\"forge:separate_transforms\",\"base\":{\"parent\":\"oldmod:item/umbrella_gui\"},"
                + "\"perspectives\":{\"gui\":{\"parent\":\"oldmod:item/umbrella_held\"},\"GUI\":{\"parent\":\"oldmod:item/umbrella_gui\"}}}";
        JsonObject model = definition(run(MigrationContext.Loader.FORGE, "26.3", umbrella(twice)), "umbrella");
        JsonArray cases = model.getAsJsonArray("cases");
        assertEquals(1, cases.size(), "the select refuses a context named in two cases");
        assertEquals("[\"gui\"]", cases.get(0).getAsJsonObject().getAsJsonArray("when").toString());
        assertEquals(plain("oldmod:item/umbrella_held"), cases.get(0).getAsJsonObject().get("model"));
    }

    @Test
    @DisplayName("A Fabric jar's model keeps the plain definition, since the Forge loader means nothing there")
    void fabricJarsKeepThePlainModel() throws IOException {
        JsonObject model = definition(run(MigrationContext.Loader.FABRIC, "26.2", umbrella(UMBRELLA)), "umbrella");
        assertEquals(plain("oldmod:item/umbrella"), model);
    }

    @Test
    @DisplayName("A separate_transforms model with no base keeps the plain model rather than show nothing")
    void noBaseKeepsThePlainModel() throws IOException {
        String noBase = "{\"loader\":\"forge:separate_transforms\",\"perspectives\":{\"gui\":{\"parent\":\"oldmod:item/umbrella_gui\"}}}";
        JsonObject model = definition(run(MigrationContext.Loader.FORGE, "26.2", umbrella(noBase)), "umbrella");
        assertEquals(plain("oldmod:item/umbrella"), model);
    }
}
