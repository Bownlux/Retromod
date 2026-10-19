/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.trimstubs.ArmorTrim;
import com.retromod.shim.common.trimstubs.Codec;
import com.retromod.shim.common.trimstubs.DataResult;
import com.retromod.shim.common.trimstubs.Decoder;
import com.retromod.shim.common.trimstubs.DynamicOps;
import com.retromod.shim.common.trimstubs.EquipmentClientInfo;
import com.retromod.shim.common.trimstubs.Holder;
import com.retromod.shim.common.trimstubs.Identifier;
import com.retromod.shim.common.trimstubs.JsonOps;
import com.retromod.shim.common.trimstubs.Minecraft;
import com.retromod.shim.common.trimstubs.RenderSystem;
import com.retromod.shim.common.trimstubs.RenderType;
import com.retromod.shim.common.trimstubs.RenderTypes;
import com.retromod.shim.common.trimstubs.Resource;
import com.retromod.shim.common.trimstubs.ResourceKey;
import com.retromod.shim.common.trimstubs.ResourceManager;
import com.retromod.shim.common.trimstubs.ResourceProvider;
import com.retromod.shim.common.trimstubs.StrictJsonParser;
import com.retromod.shim.common.trimstubs.TrimMaterial;
import com.retromod.shim.common.trimstubs.TrimPattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * 26.3 removed the armor trim atlas, so a custom armor layer built for 26.2 that draws trims, as a
 * player model mod's breast armor layer does, failed with {@code NoSuchFieldError} while the game
 * built its player renderers. The atlas, its render types and the sprite names come back from
 * {@link com.retromod.shim.common.embedded.LegacyArmorTrims}.
 */
class LegacyArmorTrimBridgeTest {

    private static final String SHEETS = LegacyArmorTrimBridge.SHEETS;
    private static final String ARMOR_TRIM = LegacyArmorTrimBridge.ARMOR_TRIM;
    private static final String IDENTIFIER = LegacyArmorTrimBridge.IDENTIFIER;
    private static final String RENDER_TYPE = LegacyArmorTrimBridge.RENDER_TYPE;
    private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
    private static final String HELPER = LegacyArmorTrimBridge.HELPER;
    private static final String LAYER = "test/mod/TrimLayer";

    private static final String EQUIPMENT_INFO = "net/minecraft/client/resources/model/EquipmentClientInfo";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";

    /** The stand-ins' names on the host. */
    private static final Map<String, String> HOST_NAMES = Map.ofEntries(
            hostName(Identifier.class, IDENTIFIER),
            hostName(Holder.class, "net/minecraft/core/Holder"),
            hostName(ArmorTrim.class, ARMOR_TRIM),
            hostName(TrimPattern.class, "net/minecraft/world/item/equipment/trim/TrimPattern"),
            hostName(TrimMaterial.class, LegacyArmorTrimBridge.TRIM_MATERIAL),
            hostName(RenderType.class, RENDER_TYPE),
            hostName(RenderTypes.class, "net/minecraft/client/renderer/rendertype/RenderTypes"),
            hostName(ResourceKey.class, RESOURCE_KEY),
            hostName(EquipmentClientInfo.class, EQUIPMENT_INFO),
            hostName(EquipmentClientInfo.TrimOverride.class, EQUIPMENT_INFO + "$TrimOverride"),
            hostName(EquipmentClientInfo.TrimPredicate.class, EQUIPMENT_INFO + "$TrimPredicate"),
            hostName(Decoder.class, "com/mojang/serialization/Decoder"),
            hostName(Codec.class, "com/mojang/serialization/Codec"),
            hostName(DynamicOps.class, "com/mojang/serialization/DynamicOps"),
            hostName(JsonOps.class, "com/mojang/serialization/JsonOps"),
            hostName(DataResult.class, "com/mojang/serialization/DataResult"),
            hostName(StrictJsonParser.class, "net/minecraft/util/StrictJsonParser"),
            hostName(Minecraft.class, MINECRAFT),
            hostName(RenderSystem.class, "com/mojang/blaze3d/systems/RenderSystem"),
            hostName(ResourceProvider.class, "net/minecraft/server/packs/resources/ResourceProvider"),
            hostName(ResourceManager.class, "net/minecraft/server/packs/resources/ResourceManager"),
            hostName(Resource.class, "net/minecraft/server/packs/resources/Resource"));

    /** The trim stand-ins every helper run needs. */
    private static final List<Class<?>> TRIM_STANDS = List.of(Identifier.class, Holder.class, ArmorTrim.class,
            TrimPattern.class, TrimMaterial.class, RenderType.class, RenderTypes.class);

    /** The client, its assets and the host codec, for reading an equipment asset's trim overrides. */
    private static final List<Class<?>> CLIENT_STANDS = List.of(ResourceKey.class, EquipmentClientInfo.class,
            EquipmentClientInfo.TrimOverride.class, EquipmentClientInfo.TrimPredicate.class, Decoder.class,
            Codec.class, DynamicOps.class, JsonOps.class, DataResult.class, StrictJsonParser.class, Minecraft.class,
            RenderSystem.class, ResourceProvider.class, ResourceManager.class, Resource.class);

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyArmorTrimBridge.registerRedirects(transformer, LegacyArmorTrimBridge.Lost.ALL);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the removed members become calls into the embedded trim atlas helper")
    void removedMembersCallTheHelper() {
        ClassNode layer = node(transformer.transformClass(trimLayer(), LAYER + ".class"));

        assertEquals(List.of("call " + HELPER + ".sheet()Ljava/lang/Object;", "cast " + IDENTIFIER),
                instructions(layer, "sheet"));
        assertEquals(List.of("call " + HELPER + ".armorTrimsSheet(Z)Ljava/lang/Object;", "cast " + RENDER_TYPE),
                instructions(layer, "renderType"));
        assertEquals(List.of("call " + HELPER + ".layerAssetId(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)"
                + "Ljava/lang/Object;", "cast " + IDENTIFIER), instructions(layer, "sprite"));
        assertEquals(List.of("call " + HELPER + ".armorDecalCutoutNoCull(Ljava/lang/Object;)Ljava/lang/Object;",
                "cast " + RENDER_TYPE), instructions(layer, "decalType"));
        assertNotNull(transformer.getSyntheticClasses().get(HELPER), "the helper is embedded with the mod");
    }

    @Test
    @DisplayName("the helper names the old sprite, hands back its atlas id, and builds 26.3's trim render types")
    void helperRunsAgainstTheHostShape() throws Exception {
        HostStubs stubs = trimHost(TRIM_STANDS);

        Object iron = trim(stubs, "minecraft", "coast", "minecraft", "trim/iron");
        assertEquals("minecraft:trims/entity/humanoid/coast_iron",
                String.valueOf(stubs.method(LAYER, "sprite").invoke(null, iron, "trims/entity/humanoid", null)),
                "the name 26.2's atlas gave an iron coast trim");
        Object ruby = trim(stubs, "examplemod", "spiral", "examplemod", "trim/ruby");
        assertEquals("examplemod:trims/entity/humanoid_leggings/spiral_examplemod.trim.ruby",
                String.valueOf(stubs.method(LAYER, "sprite").invoke(null, ruby, "trims/entity/humanoid_leggings", null)));

        Object sheet = stubs.method(LAYER, "sheet").invoke(null);
        assertEquals("retromod:textures/atlas/legacy_armor_trims/shared.png", String.valueOf(sheet),
                "a copy that was never embedded into a mod");

        Object plain = stubs.method(LAYER, "renderType").invoke(null, false);
        Object decal = stubs.method(LAYER, "renderType").invoke(null, true);
        Class<?> renderType = stubs.load(RENDER_TYPE);
        assertEquals(sheet, renderType.getField("texture").get(plain), "the render type draws from the rebuilt atlas");
        assertEquals(false, renderType.getField("decal").get(plain));
        assertEquals(true, renderType.getField("decal").get(decal));
        assertSame(plain, stubs.method(LAYER, "renderType").invoke(null, false), "built once per decal flag");

        Object ownAtlas = stubs.load(IDENTIFIER).getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, "examplemod", "textures/atlas/gems.png");
        Object ownDecal = stubs.method(LAYER, "decalType").invoke(null, ownAtlas);
        assertEquals(ownAtlas, renderType.getField("texture").get(ownDecal), "a mod's own atlas keeps its texture");
        assertEquals(true, renderType.getField("decal").get(ownDecal));
    }

    @Test
    @DisplayName("an equipment asset's trim overrides pick the sprite as 26.3 does, so iron trims on iron armor are darker")
    void equipmentTrimOverridesPickTheSprite() throws Exception {
        List<Class<?>> stands = new ArrayList<>(TRIM_STANDS);
        stands.addAll(CLIENT_STANDS);
        HostStubs stubs = trimHost(stands);
        clientAsset(stubs, "minecraft", "equipment/iron.json", """
                {"trim_overrides": [
                  {"when": {"material": "minecraft:trim/iron"}, "palette": "minecraft:trim/iron_darker"},
                  {"when": {"material": "minecraft:trim/iron"}, "palette": "minecraft:trim/gold"}
                ]}""");
        clientAsset(stubs, "minecraft", "equipment/gold.json", """
                {"trim_overrides": [{"when": {"material": "minecraft:trim/gold"}, "palette": "minecraft:trim/gold_darker"}]}""");
        clientAsset(stubs, "examplemod", "equipment/bronze.json", """
                {"trim_overrides": [{"when": {"material": "minecraft:trim/iron"}, "texture": "examplemod:bronze_coast"}]}""");

        Object iron = trim(stubs, "minecraft", "coast", "minecraft", "trim/iron");
        String layer = "trims/entity/humanoid";
        assertEquals("minecraft:trims/entity/humanoid/coast_iron_darker",
                sprite(stubs, iron, layer, asset(stubs, "minecraft", "iron")),
                "iron armor's first matching override swaps in the darker palette");
        assertEquals("minecraft:trims/entity/humanoid/coast_iron",
                sprite(stubs, iron, layer, asset(stubs, "minecraft", "gold")),
                "an override for another material keeps the material's own palette");
        assertEquals("minecraft:trims/entity/humanoid/coast_iron",
                sprite(stubs, iron, layer, asset(stubs, "minecraft", "netherite")),
                "an equipment asset without a client file keeps the material's own palette");
        assertEquals("examplemod:trims/entity/humanoid/bronze_coast",
                sprite(stubs, iron, layer, asset(stubs, "examplemod", "bronze")),
                "an override without a palette draws its own texture unrecolored, as 26.3 does");
    }

    @Test
    @DisplayName("each mod's embedded copy builds its atlas under its own id, so none closes another's")
    void eachEmbeddedCopyHasItsOwnAtlas() throws Exception {
        byte[] layer = transformer.transformClass(trimLayer(), LAYER + ".class");
        String first = String.valueOf(sheetOfEmbeddedCopy(layer, "examplemod.jar"));
        String second = String.valueOf(sheetOfEmbeddedCopy(layer, "othermod.jar"));

        assertTrue(first.matches("retromod:textures/atlas/legacy_armor_trims/examplemod_[0-9a-f]{12}\\.png"), first);
        assertTrue(second.matches("retromod:textures/atlas/legacy_armor_trims/othermod_[0-9a-f]{12}\\.png"), second);
    }

    @Test
    @DisplayName("only members the host lost are bridged, and only once trims are recolored from palettes")
    void hostGate() {
        ClassNode oldSheets = sheets(true, true);
        ClassNode oldTrim = withMethod(ARMOR_TRIM, LegacyArmorTrimBridge.LAYER_ASSET_ID, LegacyArmorTrimBridge.LAYER_ASSET_ID_DESC);
        ClassNode newTrim = withMethod(ARMOR_TRIM, "pattern", "()Lnet/minecraft/core/Holder;");
        ClassNode paletted = withMethod(LegacyArmorTrimBridge.TRIM_MATERIAL, "paletteId", "()L" + IDENTIFIER + ";");
        ClassNode assetGroups = withMethod(LegacyArmorTrimBridge.TRIM_MATERIAL, "assets",
                "()Lnet/minecraft/world/item/equipment/trim/MaterialAssetGroup;");

        ClassNode oldRenderTypes = withMethod(LegacyArmorTrimBridge.RENDER_TYPES,
                LegacyArmorTrimBridge.DECAL_TYPE, LegacyArmorTrimBridge.DECAL_TYPE_DESC);
        ClassNode newRenderTypes = withMethod(LegacyArmorTrimBridge.RENDER_TYPES, "armorTrim",
                "(L" + IDENTIFIER + ";Z)L" + RENDER_TYPE + ";");

        assertEquals(LegacyArmorTrimBridge.Lost.NONE,
                LegacyArmorTrimBridge.lostMembers(oldSheets, oldRenderTypes, oldTrim, assetGroups), "26.2");
        assertEquals(LegacyArmorTrimBridge.Lost.ALL,
                LegacyArmorTrimBridge.lostMembers(sheets(false, false), newRenderTypes, newTrim, paletted), "26.3");
        assertEquals(LegacyArmorTrimBridge.Lost.ALL, LegacyArmorTrimBridge.lostMembers(null, null, newTrim, paletted),
                "a dedicated server has no Sheets or RenderTypes, and client code naming them never runs there");
        assertEquals(new LegacyArmorTrimBridge.Lost(false, true, false, false),
                LegacyArmorTrimBridge.lostMembers(sheets(true, false), oldRenderTypes, oldTrim, paletted),
                "a host that kept three of the members keeps them");
    }

    @Test
    @DisplayName("the 26.3 shim registers the bridge, and offline the host version decides")
    void registeredByTheTwentySixThreeShim() {
        String savedTarget = RetromodVersion.TARGET_MC_VERSION;
        try {
            ClassNode kept = layerTransformedFor("26.2");
            assertEquals(List.of("field " + SHEETS + "." + LegacyArmorTrimBridge.SHEET), instructions(kept, "sheet"),
                    "26.2 keeps its atlas");

            ClassNode bridged = layerTransformedFor("26.3");
            for (String method : List.of("sheet", "renderType", "sprite", "decalType")) {
                List<String> found = instructions(bridged, method);
                assertTrue(found.get(0).startsWith("call " + HELPER + "."), method + " calls the helper: " + found);
            }
        } finally {
            RetromodVersion.TARGET_MC_VERSION = savedTarget;
        }
    }

    @Test
    @DisplayName("the real jars: 26.2 had every member, 26.3 and NeoForge's 26.3 have none and use palettes")
    void matchesTheClientJars() throws Exception {
        Path v262 = library("com/mojang/minecraft/26.2/minecraft-26.2-client.jar");
        Path v263 = library("com/mojang/minecraft/26.3/minecraft-26.3-client.jar");
        Assumptions.assumeTrue(v262 != null && v263 != null, "the 26.2 and 26.3 client jars are present");

        assertEquals(LegacyArmorTrimBridge.Lost.NONE, lostOn(v262), "26.2 keeps its atlas");
        ClassNode oldSheets = read(v262, SHEETS);
        assertTrue(oldSheets.fields.stream().anyMatch(f -> f.name.equals(LegacyArmorTrimBridge.SHEET)
                && f.desc.equals(LegacyArmorTrimBridge.SHEET_DESC)), "26.2 field shape");
        assertTrue(oldSheets.methods.stream().anyMatch(m -> m.name.equals(LegacyArmorTrimBridge.SHEET_TYPE)
                && m.desc.equals(LegacyArmorTrimBridge.SHEET_TYPE_DESC)), "26.2 render type shape");
        assertTrue(read(v262, ARMOR_TRIM).methods.stream().anyMatch(m -> m.name.equals(LegacyArmorTrimBridge.LAYER_ASSET_ID)
                && m.desc.equals(LegacyArmorTrimBridge.LAYER_ASSET_ID_DESC)), "26.2 sprite name shape");

        assertEquals(LegacyArmorTrimBridge.Lost.ALL, lostOn(v263), "26.3 removed them all");
        Path neoForge = library("net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar");
        if (neoForge != null) {
            assertEquals(LegacyArmorTrimBridge.Lost.ALL, lostOn(neoForge), "NeoForge does not patch them back");
        }
    }

    @Test
    @DisplayName("the real jars: 26.3's trim render types build what 26.2's sheet render types built")
    void trimRenderTypesMatchTheOldSheetTypes() throws Exception {
        Path v262 = library("com/mojang/minecraft/26.2/minecraft-26.2-client.jar");
        Path v263 = library("com/mojang/minecraft/26.3/minecraft-26.3-client.jar");
        Assumptions.assumeTrue(v262 != null && v263 != null, "the 26.2 and 26.3 client jars are present");
        String renderTypes = "net/minecraft/client/renderer/rendertype/RenderTypes";
        ClassNode old = readWithCode(v262, renderTypes);
        ClassNode current = readWithCode(v263, renderTypes);

        List<String> plain = renderSetup(old, "armor_cutout_no_cull");
        assertEquals(plain, renderSetup(current, "armor_trim"), "armorTrimsSheet(false) was armorCutoutNoCull(sheet)");
        assertTrue(plain.contains("net/minecraft/client/renderer/RenderPipelines.ARMOR_CUTOUT_NO_CULL"), plain.toString());
        assertEquals(renderSetup(old, "armor_decal_cutout_no_cull"), renderSetup(current, "armor_trim_decal"),
                "armorTrimsSheet(true) was createArmorDecalCutoutNoCull(sheet)");
    }

    // --- classes under test -----------------------------------------------------------------

    /**
     * The constants a render type named {@code name} reads and the builder steps it takes. A
     * constant built in the static initializer runs from its name to the field it is stored in; a
     * factory or memoized lambda is the whole method that names it.
     */
    private static List<String> renderSetup(ClassNode renderTypes, String name) {
        for (MethodNode method : renderTypes.methods) {
            boolean named = false;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && name.equals(ldc.cst)) named = true;
            }
            if (!named) continue;
            boolean windowed = method.name.equals("<clinit>");
            boolean inWindow = !windowed;
            List<String> steps = new ArrayList<>();
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) {
                    if (name.equals(ldc.cst)) {
                        inWindow = true;
                        continue;
                    }
                    if (inWindow) steps.add("\"" + ldc.cst + "\"");
                } else if (!inWindow) {
                    continue;
                } else if (insn instanceof FieldInsnNode field) {
                    if (windowed && field.getOpcode() == PUTSTATIC) return steps;
                    steps.add(field.owner + "." + field.name);
                } else if (insn instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/client/renderer/rendertype/RenderSetup$RenderSetupBuilder")
                        && !call.name.equals("createRenderSetup")) {
                    steps.add(call.name);
                }
            }
            return steps;
        }
        throw new AssertionError("no render type named " + name);
    }

    private static ClassNode readWithCode(Path jar, String owner) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes()).accept(node, 0);
            return node;
        }
    }

    /**
     * A layer that reads the atlas id, asks for the sheet render type, and names a sprite the way
     * a 26.2 armor layer does.
     */
    private static byte[] trimLayer() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, LAYER, null, "java/lang/Object", null);

        MethodVisitor sheet = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "sheet", "()L" + IDENTIFIER + ";", null, null);
        sheet.visitCode();
        sheet.visitFieldInsn(GETSTATIC, SHEETS, LegacyArmorTrimBridge.SHEET, LegacyArmorTrimBridge.SHEET_DESC);
        sheet.visitInsn(ARETURN);
        sheet.visitMaxs(0, 0);
        sheet.visitEnd();

        MethodVisitor type = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "renderType", "(Z)L" + RENDER_TYPE + ";", null, null);
        type.visitCode();
        type.visitVarInsn(ILOAD, 0);
        type.visitMethodInsn(INVOKESTATIC, SHEETS, LegacyArmorTrimBridge.SHEET_TYPE, LegacyArmorTrimBridge.SHEET_TYPE_DESC, false);
        type.visitInsn(ARETURN);
        type.visitMaxs(0, 0);
        type.visitEnd();

        MethodVisitor sprite = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "sprite",
                "(L" + ARMOR_TRIM + ";Ljava/lang/String;L" + RESOURCE_KEY + ";)L" + IDENTIFIER + ";", null, null);
        sprite.visitCode();
        sprite.visitVarInsn(ALOAD, 0);
        sprite.visitVarInsn(ALOAD, 1);
        sprite.visitVarInsn(ALOAD, 2);
        sprite.visitMethodInsn(INVOKEVIRTUAL, ARMOR_TRIM, LegacyArmorTrimBridge.LAYER_ASSET_ID,
                LegacyArmorTrimBridge.LAYER_ASSET_ID_DESC, false);
        sprite.visitInsn(ARETURN);
        sprite.visitMaxs(0, 0);
        sprite.visitEnd();

        MethodVisitor decal = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "decalType",
                "(L" + IDENTIFIER + ";)L" + RENDER_TYPE + ";", null, null);
        decal.visitCode();
        decal.visitVarInsn(ALOAD, 0);
        decal.visitMethodInsn(INVOKESTATIC, LegacyArmorTrimBridge.RENDER_TYPES, LegacyArmorTrimBridge.DECAL_TYPE,
                LegacyArmorTrimBridge.DECAL_TYPE_DESC, false);
        decal.visitInsn(ARETURN);
        decal.visitMaxs(0, 0);
        decal.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Embeds the helper into a one-class mod jar under {@code key} and asks the copy for its atlas id. */
    private Object sheetOfEmbeddedCopy(byte[] layer, String key) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(LAYER + ".class"));
            zip.write(layer);
            zip.closeEntry();
        }
        SyntheticEmbedder.ByteEmbeddingResult embedded = SyntheticEmbedder.embedIntoJarBytes(bytes.toByteArray(), key, transformer);
        assertTrue(embedded.succeeded() && embedded.embeddedCount() > 0, "the helper was embedded");

        HostStubs stubs = new HostStubs();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(embedded.jarBytes()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().endsWith(".class")) stubs.add(zip.readAllBytes());
            }
        }
        stubs.add(asHost(Identifier.class));
        return stubs.method(LAYER, "sheet").invoke(null);
    }

    /** The trim layer after the whole 26.2 to 26.3 shim, offline, for a host of {@code version}. */
    private ClassNode layerTransformedFor(String version) {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = version;
        Mc26_2To26_3CoreMoves.register(transformer);
        return node(transformer.transformClass(trimLayer(), LAYER + ".class"));
    }

    /** The embedded helper, the transformed layer and {@code stands} under their host names. */
    private HostStubs trimHost(List<Class<?>> stands) throws Exception {
        HostStubs stubs = new HostStubs()
                .add(HELPER, transformer.getSyntheticClasses().get(HELPER))
                .add(LAYER, transformer.transformClass(trimLayer(), LAYER + ".class"));
        for (Class<?> stand : stands) {
            stubs.add(asHost(stand));
        }
        return stubs;
    }

    /** Puts a client asset where the stand-in client's resource manager finds it. */
    @SuppressWarnings("unchecked")
    private static void clientAsset(HostStubs stubs, String namespace, String path, String contents) throws Exception {
        Map<Object, String> resources = (Map<Object, String>) stubs.load(MINECRAFT).getField("RESOURCES").get(null);
        resources.put(identifier(stubs, namespace, path), contents);
    }

    /** The equipment asset key {@code namespace:path}. */
    private static Object asset(HostStubs stubs, String namespace, String path) throws Exception {
        return stubs.load(RESOURCE_KEY).getConstructors()[0].newInstance(identifier(stubs, namespace, path));
    }

    /** The sprite the transformed layer's {@code layerAssetId} call names. */
    private static String sprite(HostStubs stubs, Object trim, String layerPrefix, Object equipmentAsset) throws Exception {
        return String.valueOf(stubs.method(LAYER, "sprite").invoke(null, trim, layerPrefix, equipmentAsset));
    }

    private static Object identifier(HostStubs stubs, String namespace, String path) throws Exception {
        return stubs.load(IDENTIFIER).getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, namespace, path);
    }

    /** A trim of {@code pattern} in the palette {@code paletteNamespace:palettePath}, built from the stand-ins. */
    private static Object trim(HostStubs stubs, String patternNamespace, String pattern,
            String paletteNamespace, String palettePath) throws Exception {
        Class<?> identifier = stubs.load(IDENTIFIER);
        Object texture = identifier.getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, patternNamespace, pattern);
        Object palette = identifier.getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, paletteNamespace, palettePath);
        Class<?> patternType = stubs.load("net/minecraft/world/item/equipment/trim/TrimPattern");
        Class<?> materialType = stubs.load(LegacyArmorTrimBridge.TRIM_MATERIAL);
        Object patternValue = patternType.getConstructor(identifier).newInstance(texture);
        Object materialValue = materialType.getConstructor(identifier).newInstance(palette);
        Class<?> holder = stubs.load("net/minecraft/core/Holder");
        Object armorTrim = stubs.load(ARMOR_TRIM).getConstructor(holder, holder)
                .newInstance(holderOf(holder, materialValue), holderOf(holder, patternValue));
        return armorTrim;
    }

    private static Object holderOf(Class<?> holder, Object value) {
        return Proxy.newProxyInstance(holder.getClassLoader(), new Class<?>[] {holder}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "value" -> value;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> "Holder[" + value + "]";
                });
    }

    private static Map.Entry<String, String> hostName(Class<?> stand, String hostName) {
        return Map.entry(Type.getInternalName(stand), hostName);
    }

    /** A stand-in's bytes under its host name. */
    private static byte[] asHost(Class<?> stand) throws Exception {
        try (InputStream in = stand.getClassLoader().getResourceAsStream(Type.getInternalName(stand) + ".class")) {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(in.readAllBytes()).accept(new ClassRemapper(writer, new SimpleRemapper(HOST_NAMES)), 0);
            return writer.toByteArray();
        }
    }

    private static ClassNode sheets(boolean field, boolean method) {
        ClassNode node = new ClassNode();
        node.name = SHEETS;
        if (field) {
            node.fields.add(new org.objectweb.asm.tree.FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL,
                    LegacyArmorTrimBridge.SHEET, LegacyArmorTrimBridge.SHEET_DESC, null, null));
        }
        if (method) {
            node.methods.add(new MethodNode(ACC_PUBLIC | ACC_STATIC, LegacyArmorTrimBridge.SHEET_TYPE,
                    LegacyArmorTrimBridge.SHEET_TYPE_DESC, null, null));
        }
        return node;
    }

    private static ClassNode withMethod(String owner, String name, String desc) {
        ClassNode node = new ClassNode();
        node.name = owner;
        node.methods.add(new MethodNode(ACC_PUBLIC, name, desc, null, null));
        return node;
    }

    private static LegacyArmorTrimBridge.Lost lostOn(Path jar) throws Exception {
        return LegacyArmorTrimBridge.lostMembers(read(jar, SHEETS), read(jar, LegacyArmorTrimBridge.RENDER_TYPES),
                read(jar, ARMOR_TRIM), read(jar, LegacyArmorTrimBridge.TRIM_MATERIAL));
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Calls, field reads and casts in {@code method}. */
    private static List<String> instructions(ClassNode owner, String method) {
        List<String> found = new ArrayList<>();
        for (MethodNode candidate : owner.methods) {
            if (!candidate.name.equals(method)) continue;
            for (AbstractInsnNode insn : candidate.instructions) {
                if (insn instanceof MethodInsnNode call) found.add("call " + call.owner + "." + call.name + call.desc);
                else if (insn instanceof FieldInsnNode field) found.add("field " + field.owner + "." + field.name);
                else if (insn instanceof TypeInsnNode cast && cast.getOpcode() == CHECKCAST) found.add("cast " + cast.desc);
            }
        }
        return found;
    }

    private static Path library(String path) {
        Path jar = Path.of(System.getProperty("user.home", ""), "Library/Application Support/PrismLauncher/libraries", path);
        return Files.isRegularFile(jar) ? jar : null;
    }

    private static ClassNode read(Path jar, String owner) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
            return node;
        }
    }
}
