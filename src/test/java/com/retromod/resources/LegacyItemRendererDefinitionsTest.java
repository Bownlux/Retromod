/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.shim.forge.LegacyForgeItemRendererBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** An old Forge mod's builtin/entity items get a model that runs their old renderer where its constructor adds the type. */
class LegacyItemRendererDefinitionsTest {

    private static final String TYPE = "gunmod:retromod_legacy_item";
    private static final String MOD_CLASS = "gunmod/GunMod.class";
    private static final String FORGE_MOD = "Lnet/minecraftforge/fml/common/Mod;";
    private static final String NEOFORGE_MOD = "Lnet/neoforged/fml/common/Mod;";
    private static final String NEO_INSTALLER = com.retromod.shim.neoforge.LegacyNeoForgeItemRendererBridge.INSTALLER;
    // A shared template whose children override some views: the nearest model that names a view decides it.
    private static final String TEMPLATE = "{\"parent\":\"minecraft:builtin/entity\",\"gui_light\":\"front\",\"display\":{"
            + "\"gui\":{\"rotation\":[30,45,0],\"scale\":[0.6,0.6,0.6]},"
            + "\"ground\":{\"translation\":[0,3,0],\"scale\":[0.25,0.25,0.25]},"
            + "\"head\":{\"scale\":[2,2,2]},"
            + "\"thirdperson_lefthand\":{\"rotation\":[0,90,0]}}}";

    /** A Forge 1.20.1 mod with a gun on a template, a statue drawn straight on builtin/entity, and a plain gem. */
    private static Map<String, byte[]> mod(boolean installs) {
        return mod(installs ? LegacyForgeItemRendererBridge.INSTALLER : null, FORGE_MOD);
    }

    /** The same mod whose {@code annotation} @Mod constructor calls {@code installer}, or no install when it is null. */
    private static Map<String, byte[]> mod(String installer, String annotation) {
        Map<String, byte[]> files = new LinkedHashMap<>(entries(
                "assets/gunmod/models/item/template_gun.json", TEMPLATE,
                "assets/gunmod/models/item/rifle.json", "{\"parent\":\"gunmod:item/template_gun\",\"textures\":{"
                        + "\"particle\":\"gunmod:item/rifle_icon\",\"layer0\":\"gunmod:item/rifle_icon\"},\"display\":{"
                        + "\"gui\":{\"rotation\":[0,0,0],\"translation\":[0,0,0],\"scale\":[1,1,1]},"
                        + "\"thirdperson_righthand\":{\"translation\":[0,2,1]},"
                        + "\"firstperson_righthand\":{\"translation\":[0,1,0]},\"firstperson_lefthand\":{}}}",
                "assets/gunmod/textures/item/rifle_icon.png", "png",
                "assets/gunmod/models/item/statue.json", "{\"parent\":\"builtin/entity\"}",
                "assets/gunmod/models/item/gem.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"gunmod:item/gem\"}}"));
        files.put(MOD_CLASS, modClass("gunmod/GunMod", "gunmod", installer, annotation));
        return files;
    }

    private static MapResourceTree run(String target, MigrationContext.Loader loader, Map<String, byte[]> files) throws IOException {
        MapResourceTree tree = new MapResourceTree(files);
        MigrationContext context = MigrationContext.builder(target).sourceMcVersion("1.20.1").targetLoader(loader)
                .sourceLoader(MigrationContext.Loader.FORGE).declaredNamespaces(List.of("gunmod")).build();
        new ModResourceMigrations(List.of(new ClientItemDefinitionMigrator())).run(tree, context);
        return tree;
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A builtin/entity item runs its renderer with the old display transforms, its flat icon as the fallback")
    void builtinEntityItemsRunTheirRenderer(String target) throws IOException {
        MapResourceTree tree = run(target, MigrationContext.Loader.FORGE, mod(true));

        JsonObject rifle = definition(tree, "rifle");
        assertEquals(TYPE, rifle.get("type").getAsString());
        assertEquals("gunmod:item/retromod/item_renderer/rifle", rifle.get("model").getAsString());
        assertEquals(json("{\"type\":\"minecraft:model\",\"model\":\"gunmod:item/retromod/builtin_entity/rifle\"}"),
                rifle.getAsJsonObject("fallback"), "the flat icon draws where the renderer is missing or fails");

        JsonObject transforms = model(tree, "gunmod/models/item/retromod/item_renderer/rifle");
        assertFalse(transforms.has("parent"), "the host has no builtin/entity to inherit");
        assertEquals("front", transforms.get("gui_light").getAsString());
        assertEquals("gunmod:item/rifle_icon", transforms.getAsJsonObject("textures").get("particle").getAsString());
        JsonObject display = transforms.getAsJsonObject("display");
        assertEquals(json("{\"rotation\":[0,0,0],\"translation\":[0,0,0],\"scale\":[1,1,1]}"), display.get("gui"),
                "a view the child names wins over the template's, even an identity one, as Forge 1.20.1 read it");
        assertEquals(json("{\"scale\":[2,2,2]}"), display.get("head"), "a view the child leaves out comes from the template");
        assertEquals(json("{\"translation\":[0,2,1]}"), display.get("thirdperson_righthand"));
        assertEquals(json("{\"translation\":[0,2,1]}"), display.get("thirdperson_lefthand"),
                "a model that sets only the right hand gives the left the same, ahead of its parent's left hand");
        assertEquals(json("{\"translation\":[0,1,0]}"), display.get("firstperson_righthand"));
        assertEquals(json("{}"), display.get("firstperson_lefthand"), "an explicit left hand stays, even an empty one");
        assertEquals(json("{\"translation\":[0,3,0],\"scale\":[0.25,0.25,0.25]}"), display.get("ground"));

        JsonObject statue = definition(tree, "statue");
        assertEquals(TYPE, statue.get("type").getAsString());
        assertFalse(statue.has("fallback"), "a statue with no icon still draws through its renderer");
        assertEquals(json("{}"), model(tree, "gunmod/models/item/retromod/item_renderer/statue"));
        assertEquals("minecraft:model", definition(tree, "gem").get("type").getAsString(), "a plain item keeps its model");
    }

    @Test
    @DisplayName("NeoForge, Fabric, Forge 26.1 and a jar whose constructor adds no type keep the flat icon")
    void otherTargetsKeepTheIcon() throws IOException {
        JsonObject icon = json("{\"type\":\"minecraft:model\",\"model\":\"gunmod:item/retromod/builtin_entity/rifle\"}");
        assertEquals(icon, definition(run("26.2", MigrationContext.Loader.NEOFORGE, mod(true)), "rifle"), "NeoForge");
        assertEquals(icon, definition(run("26.2", MigrationContext.Loader.FABRIC, mod(true)), "rifle"), "Fabric");
        assertEquals(icon, definition(run("26.1", MigrationContext.Loader.FORGE, mod(true)), "rifle"), "Forge 26.1");
        MapResourceTree without = run("26.2", MigrationContext.Loader.FORGE, mod(false));
        assertEquals(icon, definition(without, "rifle"), "no install, no type");
        assertFalse(without.exists("assets/gunmod/items/statue.json"), "and a statue without an icon keeps no definition");
        assertFalse(without.exists("assets/gunmod/models/item/retromod/item_renderer/rifle.json"));
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("On NeoForge from 26.1, a NeoForge or Forge mod whose constructor runs NeoForge's install gets the type")
    void neoForgeModsRunTheirRenderer(String target) throws IOException {
        for (String annotation : java.util.List.of(NEOFORGE_MOD, FORGE_MOD)) {
            JsonObject rifle = definition(run(target, MigrationContext.Loader.NEOFORGE, mod(NEO_INSTALLER, annotation)), "rifle");
            assertEquals(TYPE, rifle.get("type").getAsString(), annotation);
            assertEquals("gunmod:item/retromod/item_renderer/rifle", rifle.get("model").getAsString());
        }
        JsonObject icon = json("{\"type\":\"minecraft:model\",\"model\":\"gunmod:item/retromod/builtin_entity/rifle\"}");
        assertEquals(icon, definition(run(target, MigrationContext.Loader.NEOFORGE, mod(true)), "rifle"),
                "Forge's install, which never runs on NeoForge, adds no type there");
        assertEquals(icon, definition(run(target, MigrationContext.Loader.FORGE, mod(NEO_INSTALLER, NEOFORGE_MOD)), "rifle"),
                "and NeoForge's adds none on Forge");
    }

    @Test
    @DisplayName("NeoForge 1.21.11, whose item models the rebuilt type is not written for, keeps the flat icon")
    void neoForge12111KeepsTheIcon() throws IOException {
        assertEquals("minecraft:model", definition(run("1.21.11", MigrationContext.Loader.NEOFORGE,
                mod(NEO_INSTALLER, NEOFORGE_MOD)), "rifle").get("type").getAsString());
    }

    @Test
    @DisplayName("An override whose model is drawn in code runs the renderer too")
    void overrideModelsRunTheRenderer() throws IOException {
        Map<String, byte[]> files = mod(true);
        files.putAll(entries(
                "assets/gunmod/models/item/pistol.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"gunmod:item/pistol\"},"
                        + "\"overrides\":[{\"predicate\":{\"minecraft:pulling\":1},\"model\":\"gunmod:item/pistol_aim\"}]}",
                "assets/gunmod/models/item/pistol_aim.json", "{\"parent\":\"builtin/entity\"}"));
        JsonObject pistol = definition(run("26.2", MigrationContext.Loader.FORGE, files), "pistol");

        assertEquals("minecraft:condition", pistol.get("type").getAsString());
        assertEquals(TYPE, pistol.getAsJsonObject("on_true").get("type").getAsString());
        assertEquals("gunmod:item/retromod/item_renderer/pistol_aim", pistol.getAsJsonObject("on_true").get("model").getAsString());
    }

    private static JsonObject definition(MapResourceTree tree, String item) throws IOException {
        String name = "assets/gunmod/items/" + item + ".json";
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

    /** An @Mod class whose constructor calls the item renderer install Retromod injects, or one with no such call. */
    private static byte[] modClass(String name, String modId, String installer, String annotation) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitAnnotation(annotation, true).visit("value", modId);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        if (installer != null) {
            init.visitMethodInsn(INVOKESTATIC, "com/retromod/embedded/" + modId + "/" + installer, "install", "()V", false);
        }
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
