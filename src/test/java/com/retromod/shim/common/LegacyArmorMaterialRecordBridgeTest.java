/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.LegacyBaseSynthesizer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #366: ProjectE PE1.1.0 (NeoForge 1.21.1) registers its armor materials in
 * {@code Registries.ARMOR_MATERIAL}, which 1.21.2 removed, and stopped at construction on NeoForge
 * 26.2 with {@code NoSuchFieldError}.
 */
class LegacyArmorMaterialRecordBridgeTest {

    private static final String ID = "Lnet/minecraft/resources/Identifier;";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The removed registry, the old record constructor, and its layer reach the generated helpers")
    void oldMaterialShapesAreRedirected() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyArmorMaterialRecordBridge.register(transformer);

        ClassNode materials = read(transformer.transformClass(materials(), "mod/Materials"));

        List<String> calls = calls(materials);
        assertTrue(calls.contains(LegacyArmorMaterialRecordBridge.CALLS + ".armorMaterialKey"), calls.toString());
        assertTrue(calls.contains(LegacyArmorMaterialRecordBridge.CALLS + ".armorMaterialRegistry"), calls.toString());
        assertTrue(calls.contains(LegacyArmorMaterialRecordBridge.CALLS + ".create"), calls.toString());
        assertTrue(calls.contains(LegacyArmorMaterialRecordBridge.LAYER + ".<init>"), calls.toString());
        assertFalse(types(materials).contains("net/minecraft/world/item/ArmorMaterial"), "no record is built with the old constructor");
    }

    @Test
    @DisplayName("A host that still has the armor material registry is left alone")
    void olderHostsAreLeftAlone() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyArmorMaterialRecordBridge.register(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyArmorMaterialRecordBridge.CALLS));
    }

    @Test
    @DisplayName("The generated classes verify")
    void generatedClassesVerify() {
        for (byte[] generated : List.of(LegacyArmorMaterialRecordBridge.generateCalls(), LegacyArmorMaterialRecordBridge.generateLayer())) {
            new ClassReader(generated).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }
    }

    @Test
    @DisplayName("A generated base reports each adapted item once it exists")
    void baseReportsTheConstructedItem() {
        String properties = "Lnet/minecraft/world/item/Item$Properties;";
        var adapter = new LegacyBaseSynthesizer.ArgumentAdapter("support/Equipment", "adapt", "armor", properties,
                new String[0], "constructed");
        byte[] base = LegacyBaseSynthesizer.generate("gen/ArmorBase", "net/minecraft/world/item/Item", "(" + properties + ")V",
                List.of("(Lnet/minecraft/core/Holder;Lnet/minecraft/world/item/equipment/ArmorType;" + properties + ")V"), adapter);

        List<String> calls = calls(read(base));
        int superCall = calls.indexOf("net/minecraft/world/item/Item.<init>");
        assertTrue(superCall >= 0, calls.toString());
        assertEquals("support/Equipment.constructed", calls.get(superCall + 1), "reported right after super(...)");
    }

    /** A 1.21.1 materials class: the registry key, the registry, and one material with one layer. */
    private static byte[] materials() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Materials", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "build", "(" + ID + ")Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/Registries", "ARMOR_MATERIAL", "Lnet/minecraft/resources/ResourceKey;");
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/BuiltInRegistries", "ARMOR_MATERIAL", "Lnet/minecraft/core/Registry;");
        mv.visitInsn(POP);
        mv.visitTypeInsn(NEW, "net/minecraft/world/item/ArmorMaterial");
        mv.visitInsn(DUP);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitTypeInsn(NEW, LegacyArmorMaterialRecordBridge.OLD_LAYER);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, LegacyArmorMaterialRecordBridge.OLD_LAYER, "<init>", "(" + ID + ")V", false);
        mv.visitMethodInsn(INVOKESTATIC, "java/util/List", "of", "(Ljava/lang/Object;)Ljava/util/List;", true);
        mv.visitInsn(FCONST_0);
        mv.visitInsn(FCONST_0);
        mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/world/item/ArmorMaterial", "<init>", LegacyArmorMaterialRecordBridge.OLD_CTOR, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            }
        }
        return calls;
    }

    private static List<String> types(ClassNode node) {
        List<String> types = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof TypeInsnNode type) types.add(type.desc);
            }
        }
        return types;
    }
}
