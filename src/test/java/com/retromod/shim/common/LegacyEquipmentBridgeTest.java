/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.LegacyBaseSynthesizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Amethyst Equipment, a 1.20.1 mod, on 26.3: its armor material enum implemented what is now a
 * final record, and its armor and tools, rebased onto a plain item, lost their protection and
 * damage. The old interfaces are renamed per jar, and the generated bases rebuild the components.
 */
class LegacyEquipmentBridgeTest {

    @Test
    @DisplayName("a material enum implementing the old interface marks the jar as old; a record user does not")
    void detectsOldInterfaceUse() {
        byte[] material = classImplementing("mod/ModArmorMaterials", "net/minecraft/class_1741");
        byte[] tier = classImplementing("mod/ModToolMaterials", "net/minecraft/world/item/Tier");
        assertEquals(new LegacyEquipmentBridge.Usage(true, false), LegacyEquipmentBridge.usage(material));
        assertEquals(new LegacyEquipmentBridge.Usage(false, true), LegacyEquipmentBridge.usage(tier));
        assertEquals(new LegacyEquipmentBridge.Usage(false, false),
                LegacyEquipmentBridge.usage(classImplementing("mod/Plain", "java/lang/Runnable")));
    }

    @Test
    @DisplayName("the old interface is renamed to the generated one in every class of the jar")
    void renamesTheOldInterface() {
        byte[] material = classImplementing("mod/ModArmorMaterials", "net/minecraft/class_1741");
        ClassNode out = read(LegacyEquipmentBridge.rename(material,
                Map.of("net/minecraft/class_1741", LegacyEquipmentBridge.ARMOR_MATERIAL)));
        assertEquals(List.of(LegacyEquipmentBridge.ARMOR_MATERIAL), out.interfaces);
    }

    @Test
    @DisplayName("a view of a vanilla material answers every old method through the support class")
    void viewsVerify() throws Exception {
        ClassNode view = read(LegacyEquipmentBridge.generateView(LegacyEquipmentBridge.TIER_VIEW,
                LegacyEquipmentBridge.TIER, LegacyEquipmentBridge.TIER_METHODS, "net/minecraft/world/item/ToolMaterial",
                "net/minecraft/world/item/ToolMaterial", LegacyEquipmentBridge.TIER_CONSTANTS));
        for (String method : LegacyEquipmentBridge.TIER_METHODS.keySet()) {
            assertTrue(view.methods.stream().anyMatch(m -> m.name.equals(method)), method);
        }
        assertTrue(view.methods.stream().anyMatch(m -> m.name.equals("IRON")), "Tiers.IRON needs a view");
        for (MethodNode method : view.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(view.name, method);
        }
    }

    @Test
    @DisplayName("on 26.1 and 26.2 an old axe, shovel, or hoe is built on the surviving class from a converted tier")
    void survivingToolFactoriesBuildTheRealClass() throws Exception {
        ClassNode tools = read(LegacyEquipmentBridge.generateTools());
        for (String tool : List.of("axe", "shovel", "hoe")) {
            MethodNode factory = tools.methods.stream().filter(m -> m.name.equals(tool)).findFirst().orElseThrow();
            List<String> calls = Arrays.stream(factory.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
                    .map(i -> ((MethodInsnNode) i).name + ((MethodInsnNode) i).desc).toList();
            assertTrue(calls.get(1).startsWith("<init>(Lnet/minecraft/world/item/ToolMaterial;FF"), calls.toString());
        }
        assertTrue(tools.methods.stream().anyMatch(m -> m.name.equals("hoe") && m.desc.startsWith(
                "(L" + LegacyEquipmentBridge.TIER + ";IF")), "the old hoe took an int damage");
        for (MethodNode method : tools.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(tools.name, method);
        }
    }

    @Test
    @DisplayName("old armor and horse armor textures become one equipment asset with three layers")
    void migratesArmorTextures() {
        Map<String, byte[]> jar = new java.util.LinkedHashMap<>();
        jar.put("assets/minecraft/textures/models/armor/amethyst_layer_1.png", new byte[]{1});
        jar.put("assets/minecraft/textures/models/armor/amethyst_layer_2.png", new byte[]{2});
        jar.put("assets/minecraft/textures/entity/horse/armor/horse_armor_amethyst.png", new byte[]{3});

        Map<String, byte[]> added = LegacyEquipmentBridge.armorAssetEntries(jar.keySet(), jar::get);

        String asset = new String(added.get("assets/minecraft/equipment/amethyst.json"));
        for (String layer : List.of("humanoid", "humanoid_leggings", "horse_body")) {
            assertTrue(asset.contains("\"" + layer + "\": [{\"texture\": \"minecraft:amethyst\"}]"), asset);
        }
        String equipment = "assets/minecraft/textures/entity/equipment/";
        assertArrayEquals(new byte[]{2}, added.get(equipment + "humanoid_leggings/amethyst.png"));
        assertArrayEquals(new byte[]{3}, added.get(equipment + "horse_body/amethyst.png"));
    }

    @Test
    @DisplayName("a retextured vanilla armor never replaces vanilla's equipment asset")
    void keepsVanillaEquipmentAssets() {
        Map<String, byte[]> jar = new java.util.LinkedHashMap<>();
        jar.put("assets/minecraft/textures/models/armor/iron_layer_1.png", new byte[]{1});
        jar.put("assets/minecraft/textures/models/armor/iron_layer_2.png", new byte[]{2});

        Map<String, byte[]> added = LegacyEquipmentBridge.armorAssetEntries(jar.keySet(), jar::get, "26.3");

        assertFalse(added.containsKey("assets/minecraft/equipment/iron.json"),
                "vanilla's iron asset carries horse, baby and trim layers a copy would drop");
        assertArrayEquals(new byte[]{1},
                added.get("assets/minecraft/textures/entity/equipment/humanoid/iron.png"),
                "the retextured layer still applies through vanilla's own asset");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} on {1} and {2}")
    @org.junit.jupiter.params.provider.CsvSource({
            "copper, 1.21.8, 1.21.9",
            "saddle, 1.21.4, 1.21.5",
            "red_harness, 1.21.5, 1.21.6",
            "trader_llama_baby, 1.21.11, 26.1"
    })
    @DisplayName("an unnamespaced armor gets its own asset on hosts that predate vanilla's of that name")
    void vanillaEquipmentAssetsAreSkippedOnlyWhereTheHostShipsThem(String name, String before,
                                                                  String from) {
        Map<String, byte[]> jar = new java.util.LinkedHashMap<>();
        jar.put("assets/minecraft/textures/models/armor/" + name + "_layer_1.png", new byte[]{1});
        String asset = "assets/minecraft/equipment/" + name + ".json";

        assertTrue(LegacyEquipmentBridge.armorAssetEntries(jar.keySet(), jar::get, before).containsKey(asset),
                "Minecraft " + before + " has no " + name + " asset, so the armor needs its own");
        assertFalse(LegacyEquipmentBridge.armorAssetEntries(jar.keySet(), jar::get, from).containsKey(asset),
                "Minecraft " + from + " ships its own " + name + " asset");
    }

    @Test
    @DisplayName("dyeable armor keeps a dyed base layer under its overlay, and its item joins #minecraft:dyeable")
    void dyeableArmorAssetsAndTag() {
        Map<String, byte[]> jar = new java.util.LinkedHashMap<>();
        jar.put("assets/mod/textures/models/armor/cloth_layer_1.png", new byte[]{1});
        jar.put("assets/mod/textures/models/armor/cloth_layer_1_overlay.png", new byte[]{2});
        jar.put("assets/mod/models/item/cloth_helmet.json",
                "{\"parent\": \"item/generated\", \"textures\": {\"layer0\": \"mod:item/cloth_helmet\", \"layer1\": \"mod:item/cloth_helmet_overlay\"}}".getBytes());
        jar.put("assets/mod/models/item/plain.json", "{\"textures\": {\"layer0\": \"mod:item/plain\"}}".getBytes());

        Map<String, byte[]> added = LegacyEquipmentBridge.armorAssetEntries(jar.keySet(), jar::get);

        String asset = new String(added.get("assets/mod/equipment/cloth.json"));
        assertTrue(asset.contains("{\"texture\": \"mod:cloth\", \"dyeable\": {\"color_when_undyed\": -6265536}}, "
                + "{\"texture\": \"mod:cloth_overlay\"}"), asset);
        assertArrayEquals(new byte[]{2}, added.get("assets/mod/textures/entity/equipment/humanoid/cloth_overlay.png"));
        String tag = new String(added.get("data/minecraft/tags/item/dyeable.json"));
        assertTrue(tag.contains("\"id\": \"mod:cloth_helmet\", \"required\": false"), tag);
        assertFalse(tag.contains("mod:plain"), tag);
    }

    @Test
    @DisplayName("the old dyeable interface answers its color methods through the support class")
    void dyeableInterfaceVerifies() throws Exception {
        ClassNode dyeable = read(LegacyEquipmentBridge.generateDyeableInterface());
        assertEquals(List.of("getColor", "hasCustomColor", "clearColor", "setColor"),
                dyeable.methods.stream().map(m -> m.name).toList());
        for (MethodNode method : dyeable.methods) {
            assertEquals(0, method.access & ACC_ABSTRACT, method.name + " must be a default method");
            new Analyzer<>(new BasicVerifier()).analyze(dyeable.name, method);
        }
    }

    @Test
    @DisplayName("a legacy base constructor hands every old argument to the adapter with the properties")
    void baseConstructorCallsTheAdapter() {
        String properties = "Lnet/minecraft/world/item/Item$Properties;";
        var adapter = new LegacyBaseSynthesizer.ArgumentAdapter("support/Equipment", "adapt", "sword", properties);
        ClassNode base = read(LegacyBaseSynthesizer.generate("gen/LegacySwordItem", "net/minecraft/world/item/Item",
                "(" + properties + ")V", List.of("(Lgen/Tier;IF" + properties + ")V"), adapter));
        MethodNode ctor = base.methods.stream()
                .filter(m -> m.name.equals("<init>") && m.desc.startsWith("(Lgen/Tier;")).findFirst().orElseThrow();
        List<String> calls = Arrays.stream(ctor.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
                .map(i -> ((MethodInsnNode) i).owner + "." + ((MethodInsnNode) i).name).toList();
        assertEquals(List.of("java/lang/Integer.valueOf", "java/lang/Float.valueOf", "support/Equipment.adapt",
                "net/minecraft/world/item/Item.<init>"), calls);
    }

    private static byte[] classImplementing(String name, String iface) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", new String[]{iface});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
