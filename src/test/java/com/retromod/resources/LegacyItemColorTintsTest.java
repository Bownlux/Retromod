/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.shim.forge.LegacyForgeColorBridge;
import com.retromod.shim.neoforge.LegacyNeoForgeColorBridge;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Item definitions Retromod writes for an old Forge-family mod that sets up old item colors name its tint type on every tinted index. */
class LegacyItemColorTintsTest {

    private static final String TYPE = "colormod:retromod_legacy_color";
    private static final String MOD_CLASS = "colormod/ColorMod.class";

    /** An old Forge 1.20.1 mod: a two-layer gem, a leaf block item on vanilla leaves, a stone item, and a three-index block. */
    private static Map<String, byte[]> mod(boolean installsItemColors) {
        Map<String, byte[]> files = new LinkedHashMap<>(entries(
                "assets/colormod/models/item/gem.json",
                "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"colormod:item/gem\",\"layer1\":\"colormod:item/gem_glow\"}}",
                "assets/colormod/models/item/leaf.json", "{\"parent\":\"colormod:block/leaf\"}",
                "assets/colormod/models/block/leaf.json", "{\"parent\":\"minecraft:block/leaves\",\"textures\":{\"all\":\"colormod:block/leaf\"}}",
                "assets/colormod/models/item/stone.json", "{\"parent\":\"minecraft:block/cube_all\"}",
                "assets/colormod/models/item/lamp.json", "{\"parent\":\"colormod:block/lamp\"}",
                "assets/colormod/models/block/lamp.json", "{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"
                        + "\"up\":{\"texture\":\"#all\",\"tintindex\":2},\"down\":{\"texture\":\"#all\"}}}]}",
                "assets/colormod/models/item/sword.json", "{\"parent\":\"item/handheld\",\"textures\":{\"layer0\":\"colormod:item/sword\"}}",
                "assets/colormod/models/item/addon_part.json", "{\"parent\":\"otherlib:item/base\"}"));
        files.put(MOD_CLASS, modClass("colormod/ColorMod", "colormod", installsItemColors));
        return files;
    }

    private static MapResourceTree run(String target, MigrationContext.Loader loader, Map<String, byte[]> files) throws IOException {
        return run(target, loader, files, List.of("colormod"));
    }

    private static MapResourceTree run(String target, MigrationContext.Loader loader, Map<String, byte[]> files,
            List<String> declared) throws IOException {
        MapResourceTree tree = new MapResourceTree(files);
        MigrationContext context = MigrationContext.builder(target).sourceMcVersion("1.20.1").targetLoader(loader)
                .sourceLoader(MigrationContext.Loader.FORGE).declaredNamespaces(declared).build();
        new ModResourceMigrations(List.of(new ClientItemDefinitionMigrator())).run(tree, context);
        return tree;
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Each item gets one tint per tint index its model uses, through jar and vanilla parents alike")
    void tintedModelsAskTheOldItemColors(String target) throws IOException {
        MapResourceTree tree = run(target, MigrationContext.Loader.FORGE, mod(true));

        assertEquals(List.of(0, 1), tintIndexes(tree, "gem"), "item/generated tints layer N with index N");
        assertEquals(List.of(0), tintIndexes(tree, "leaf"), "vanilla's leaves model tints its faces with index 0");
        assertEquals(List.of(0, 1, 2), tintIndexes(tree, "lamp"), "a face with tintindex 2 needs indexes 0 to 2");
        assertEquals(List.of(0), tintIndexes(tree, "sword"), "item/handheld is a layered template too");
        assertEquals(List.of(0, 1, 2, 3), tintIndexes(tree, "addon_part"), "another mod's parent cannot be read, so four indexes");
        assertNull(model(tree, "stone").get("tints"), "a cube_all block item draws no tinted quad");
        assertEquals(TYPE, model(tree, "gem").getAsJsonArray("tints").get(1).getAsJsonObject().get("type").getAsString());
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("On NeoForge from 26.1 a jar whose constructor runs NeoForge's color install gets the same tints")
    void neoForgeInstallNamesTheType(String target) throws IOException {
        Map<String, byte[]> files = mod(false);
        files.put(MOD_CLASS, modClass("colormod/ColorMod", "colormod", LegacyNeoForgeColorBridge.INSTALLER));
        MapResourceTree tree = run(target, MigrationContext.Loader.NEOFORGE, files);
        assertEquals(List.of(0, 1), tintIndexes(tree, "gem"));
        assertEquals(List.of(0), tintIndexes(tree, "leaf"));
        assertNull(model(run("1.21.11", MigrationContext.Loader.NEOFORGE, files), "gem").get("tints"),
                "NeoForge 1.21.11, where Retromod adds no tint type");
        assertNull(model(run(target, MigrationContext.Loader.FORGE, files), "gem").get("tints"), "Forge runs its own install");
    }

    @Test
    @DisplayName("NeoForge, Fabric, Forge 26.1 and a jar without the item color install get no tints Retromod's types would answer")
    void otherTargetsKeepPlainDefinitions() throws IOException {
        assertNull(model(run("26.2", MigrationContext.Loader.NEOFORGE, mod(true)), "gem").get("tints"), "NeoForge");
        assertNull(model(run("26.2", MigrationContext.Loader.FABRIC, mod(true)), "gem").get("tints"), "Fabric");
        assertNull(model(run("26.1", MigrationContext.Loader.FORGE, mod(true)), "gem").get("tints"), "Forge 26.1");
        assertNull(model(run("26.2", MigrationContext.Loader.FORGE, mod(false)), "gem").get("tints"),
                "a jar whose constructors do not install item colors registers no tint type");
    }

    @Test
    @DisplayName("A jar that carries the embedded item color classes but whose constructors never install them gets no tints")
    void embeddedCopyWithoutInstallGetsNoTints() throws IOException {
        Map<String, byte[]> files = mod(false);
        String copy = "com/retromod/embedded/colormod/" + LegacyForgeColorBridge.INSTALLER;
        files.put(copy + ".class", plainClass(copy));
        String table = "com/retromod/embedded/colormod/com/retromod/legacyapi/minecraft/client/color/ItemColors";
        files.put(table + ".class", plainClass(table));
        assertNull(model(run("26.2", MigrationContext.Loader.FORGE, files), "gem").get("tints"),
                "the copy alone registers no tint type");
    }

    @Test
    @DisplayName("Of two @Mod classes in one jar, only the one that installs item colors gives its id to the tints")
    void onlyTheInstallingModNamesTheType() throws IOException {
        Map<String, byte[]> files = mod(false);
        files.put("othermod/OtherMod.class", modClass("othermod/OtherMod", "othermod", true));
        files.putAll(entries("assets/colormod/models/item/shard.json",
                "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"colormod:item/shard\"}}"));
        MapResourceTree tree = run("26.2", MigrationContext.Loader.FORGE, files, List.of("colormod", "othermod"));
        JsonArray tints = model(tree, "shard").getAsJsonArray("tints");
        assertEquals("othermod:retromod_legacy_color", tints.get(0).getAsJsonObject().get("type").getAsString(),
                "colormod's constructor installs nothing, so its items name the type othermod registers");
    }

    @Test
    @DisplayName("An item of a namespace the jar does not declare takes the jar's own mod's tint type")
    void undeclaredNamespaceUsesTheJarsMod() throws IOException {
        Map<String, byte[]> files = mod(true);
        files.putAll(entries("assets/sharedart/models/item/badge.json",
                "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"sharedart:item/badge\"}}"));
        MapResourceTree tree = run("26.2", MigrationContext.Loader.FORGE, files);
        JsonObject badge = JsonParser.parseString(text(tree.read("assets/sharedart/items/badge.json"))).getAsJsonObject()
                .getAsJsonObject("model");
        assertEquals(TYPE, badge.getAsJsonArray("tints").get(0).getAsJsonObject().get("type").getAsString());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The vanilla tint table matches the client jar's models")
    void vanillaTableMatchesTheClientJar(String version) throws IOException {
        Path jar = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft",
                version, "minecraft-" + version + "-client.jar");
        Assumptions.assumeTrue(Files.isRegularFile(jar), "no Minecraft " + version + " client jar at " + jar);
        Map<String, JsonObject> models = new TreeMap<>();
        Pattern file = Pattern.compile("assets/minecraft/models/(.+)\\.json");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                Matcher name = file.matcher(entry.getName());
                if (!name.matches()) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    JsonElement parsed = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    if (parsed.isJsonObject()) models.put("minecraft:" + name.group(1), parsed.getAsJsonObject());
                }
            }
        }
        for (String id : models.keySet()) {
            assertEquals(expectedSlots(models, id), LegacyItemColorTints.vanillaSlots(id), id);
        }
    }

    /** The table's answer for one vanilla model, worked out from the jar's own parent chain. */
    private static int expectedSlots(Map<String, JsonObject> models, String id) {
        String current = id;
        boolean ownLayers = false;
        for (int depth = 0; depth < 32; depth++) {
            if (current.equals("minecraft:builtin/generated")) return ownLayers ? 0 : LegacyItemColorTints.GENERATED;
            JsonObject model = models.get(current);
            if (model == null) return 0;
            if (model.get("textures") instanceof JsonObject textures) {
                ownLayers |= textures.keySet().stream().anyMatch(key -> key.matches("layer\\d+"));
            }
            if (model.get("elements") instanceof JsonArray elements) {
                int highest = -1;
                for (JsonElement element : elements) {
                    if (!(element.getAsJsonObject().get("faces") instanceof JsonObject faces)) continue;
                    for (String face : faces.keySet()) {
                        JsonObject value = faces.getAsJsonObject(face);
                        if (value.has("tintindex")) highest = Math.max(highest, value.get("tintindex").getAsInt());
                    }
                }
                return highest + 1;
            }
            if (!model.has("parent")) return 0;
            String parent = model.get("parent").getAsString();
            current = parent.contains(":") ? parent : "minecraft:" + parent;
        }
        return 0;
    }

    private static JsonObject model(MapResourceTree tree, String item) throws IOException {
        String name = "assets/colormod/items/" + item + ".json";
        assertTrue(tree.exists(name), "a definition for " + item);
        return JsonParser.parseString(text(tree.read(name))).getAsJsonObject().getAsJsonObject("model");
    }

    private static List<Integer> tintIndexes(MapResourceTree tree, String item) throws IOException {
        JsonArray tints = model(tree, item).getAsJsonArray("tints");
        assertNotNull(tints, item + " has tints");
        List<Integer> indexes = new ArrayList<>();
        for (JsonElement tint : tints) {
            assertEquals(TYPE, tint.getAsJsonObject().get("type").getAsString());
            indexes.add(tint.getAsJsonObject().get("index").getAsInt());
        }
        return indexes;
    }

    /** An @Mod class whose constructor calls the item color install Retromod injects, or one with no such call. */
    private static byte[] modClass(String name, String modId, boolean installsItemColors) {
        return modClass(name, modId, installsItemColors ? LegacyForgeColorBridge.INSTALLER : null);
    }

    /** An @Mod class whose constructor calls {@code installer.install()} from the jar's embedded copy, or nothing for null. */
    private static byte[] modClass(String name, String modId, String installer) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true).visit("value", modId);
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

    /** An empty class under {@code name}, whose bytes hold its own name. */
    private static byte[] plainClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
