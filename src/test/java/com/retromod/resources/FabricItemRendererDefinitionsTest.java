/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** An old Fabric mod that registers builtin item renderers gets models carrying its old display transforms. */
class FabricItemRendererDefinitionsTest {

    private static final String REGISTRY = "net/fabricmc/fabric/api/client/rendering/v1/BuiltinItemRendererRegistry";

    /** A Fabric 1.20.1 mod with a globe drawn on builtin/entity, a lamp with an icon, and, when asked, a registry call. */
    private static Map<String, byte[]> mod(boolean namesRegistry) {
        Map<String, byte[]> files = new LinkedHashMap<>(entries(
                "assets/globes/models/item/globe.json", "{\"parent\":\"builtin/entity\",\"textures\":{\"particle\":"
                        + "\"block/oak_planks\"},\"display\":{\"gui\":{\"rotation\":[30,45,0],\"scale\":[0.625,0.625,0.625]},"
                        + "\"ground\":{\"translation\":[0,3,0],\"scale\":[0.25,0.25,0.25]}}}",
                "assets/globes/models/item/lamp.json", "{\"parent\":\"builtin/entity\",\"textures\":{\"layer0\":\"globes:item/lamp\"}}",
                "assets/globes/textures/item/lamp.png", "png"));
        if (namesRegistry) {
            files.put("globes/GlobesClient.class", classReading("globes/GlobesClient", REGISTRY, "INSTANCE", "L" + REGISTRY + ";"));
        }
        return files;
    }

    private static MapResourceTree run(String target, MigrationContext.Loader loader, Map<String, byte[]> files) throws IOException {
        MapResourceTree tree = new MapResourceTree(files);
        MigrationContext context = MigrationContext.builder(target).sourceMcVersion("1.20.1").targetLoader(loader)
                .sourceLoader(MigrationContext.Loader.FABRIC).declaredNamespaces(List.of("globes")).build();
        new ModResourceMigrations(List.of(new ClientItemDefinitionMigrator())).run(tree, context);
        return tree;
    }

    @ParameterizedTest(name = "Fabric {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("A builtin/entity item gets a plain model with every old view, which its renderer draws with")
    void rendererItemsKeepTheirTransforms(String target) throws IOException {
        MapResourceTree tree = run(target, MigrationContext.Loader.FABRIC, mod(true));

        JsonObject globe = definition(tree, "globe");
        assertEquals(json("{\"type\":\"minecraft:model\",\"model\":\"globes:item/retromod/item_renderer/globe\"}"), globe);
        JsonObject model = model(tree, "globes/models/item/retromod/item_renderer/globe");
        assertFalse(model.has("parent"), "a globe without an icon draws only through its renderer");
        assertEquals("side", model.get("gui_light").getAsString(), "builtin entity models lit the GUI view from the side");
        JsonObject display = model.getAsJsonObject("display");
        assertEquals(json("{\"rotation\":[30,45,0],\"scale\":[0.625,0.625,0.625]}"), display.get("gui"));
        assertEquals(json("{}"), display.get("head"), "a view the old model left out is the identity, as before");
        assertEquals(json("{}"), display.get("on_shelf"));

        JsonObject lamp = definition(tree, "lamp");
        assertEquals("minecraft:condition", lamp.get("type").getAsString());
        assertEquals("minecraft:debug_stick_state", lamp.get("component").getAsString(), "a component no lamp carries");
        assertEquals(json("{\"type\":\"minecraft:model\",\"model\":\"globes:item/retromod/builtin_entity/lamp\"}"),
                lamp.get("on_false"), "a lamp without a renderer shows its plain flat icon");
        assertEquals(json("{\"type\":\"minecraft:model\",\"model\":\"globes:item/retromod/item_renderer/lamp\"}"),
                lamp.get("on_true"), "the transforms are named, so the game loads them for the renderer");
        JsonObject icon = model(tree, "globes/models/item/retromod/builtin_entity/lamp");
        assertEquals("minecraft:item/generated", icon.get("parent").getAsString());
        assertFalse(icon.has("display"), "the icon keeps the flat item transforms");
        JsonObject lampTransforms = model(tree, "globes/models/item/retromod/item_renderer/lamp");
        assertFalse(lampTransforms.has("parent"));
        assertEquals(json("{}"), lampTransforms.getAsJsonObject("display").get("ground"));
    }

    @ParameterizedTest(name = "Fabric {0}")
    @ValueSource(strings = {"1.21.4", "1.21.11"})
    @DisplayName("A host before 26.1, where the registry bridge does not run, keeps the flat icon")
    void olderHostsKeepTheIcon(String target) throws IOException {
        assertEquals(json("{\"type\":\"minecraft:model\",\"model\":\"globes:item/retromod/builtin_entity/lamp\"}"),
                definition(run(target, MigrationContext.Loader.FABRIC, mod(true)), "lamp"));
    }

    @Test
    @DisplayName("A Fabric jar without a registry call, Quilt aside, keeps the flat icon")
    void otherJarsKeepTheIcon() throws IOException {
        JsonObject icon = json("{\"type\":\"minecraft:model\",\"model\":\"globes:item/retromod/builtin_entity/lamp\"}");
        assertEquals(icon, definition(run("26.3", MigrationContext.Loader.FABRIC, mod(false)), "lamp"));
        assertEquals("minecraft:condition", definition(run("26.3", MigrationContext.Loader.QUILT, mod(true)), "lamp")
                .get("type").getAsString(), "Quilt runs the Fabric jar");
        assertEquals(icon, definition(run("26.3", MigrationContext.Loader.NEOFORGE, mod(true)), "lamp"),
                "NeoForge has no Fabric registry to draw through");
    }

    private static JsonObject definition(MapResourceTree tree, String item) throws IOException {
        String name = "assets/globes/items/" + item + ".json";
        assertTrue(tree.exists(name), "a definition for " + item);
        return JsonParser.parseString(text(tree.read(name))).getAsJsonObject().getAsJsonObject("model");
    }

    private static JsonObject model(MapResourceTree tree, String path) throws IOException {
        String name = "assets/" + path + ".json";
        assertTrue(tree.exists(name), name + " is written");
        return JsonParser.parseString(text(tree.read(name))).getAsJsonObject();
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
