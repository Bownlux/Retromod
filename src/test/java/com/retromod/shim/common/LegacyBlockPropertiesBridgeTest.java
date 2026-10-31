/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Fossils and Archaeology 9.3.4.0 (Forge 1.20.1) on NeoForge 1.21.1: a block built from
 * {@code Properties.copy(Blocks.BARRIER)} failed while its states were created, because 1.21's
 * barrier color reads {@code waterlogged}, and its javelin wrote {@code Item.Properties.maxStackSize},
 * which 1.20.5 removed.
 */
class LegacyBlockPropertiesBridgeTest {

    private static final String PROPERTIES = LegacyBlockPropertiesBridge.PROPERTIES;
    private static final String ITEM_PROPERTIES = "net/minecraft/world/item/Item$Properties";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("copy(block) becomes ofLegacyCopy(block) passed through the map color guard")
    void copyIsGuarded() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyBlockPropertiesBridge.register(transformer);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/ModBlocks", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "props",
                "(Lnet/minecraft/world/level/block/state/BlockBehaviour;)L" + PROPERTIES + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, PROPERTIES, "copy", LegacyBlockPropertiesBridge.COPY_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        List<String> calls = calls(transform(transformer, cw.toByteArray(), "mod/ModBlocks"));

        assertEquals(List.of(PROPERTIES + ".ofLegacyCopy", LegacyBlockPropertiesBridge.SUPPORT + ".guardMapColor"), calls);
    }

    @Test
    @DisplayName("A properties subclass writing maxStackSize sets the stack size through the components bridge")
    void inheritedStackSizeWrite() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Legacy1205MemberBridge.registerPropertiesStackSize(transformer);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/JavelinProperties", null, ITEM_PROPERTIES, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "stacksToWithDurability", "(I)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitFieldInsn(PUTFIELD, "mod/JavelinProperties", "maxStackSize", "I");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] javelin = cw.toByteArray();
        // The hierarchy is proven from the jar's own classes, as the runtime path provides them.
        transformer.setJarClassBytesProvider(name -> name.equals("mod/JavelinProperties") ? javelin : null);
        ClassNode out;
        try {
            out = transform(transformer, javelin, "mod/JavelinProperties");
        } finally {
            transformer.setJarClassBytesProvider(null);
        }

        assertEquals(List.of("com/retromod/mixin/runtime/ItemComponentsBridge.setPropertiesStackSize"), calls(out));
        assertTrue(fields(out).isEmpty(), "the removed field must not be written: " + fields(out));
    }

    @Test
    @DisplayName("A 1.20.1 mod's toString of an item keeps the path, and its own super.toString() is left alone")
    void itemNamesForOldMods() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Legacy1205MemberBridge.registerItemNames(transformer);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/Recipe", null, "java/lang/Object", null);
        MethodVisitor id = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "id", "(Ljava/lang/Object;)Ljava/lang/String;", null, null);
        id.visitCode();
        id.visitVarInsn(ALOAD, 0);
        id.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        id.visitInsn(ARETURN);
        id.visitMaxs(0, 0);
        id.visitEnd();
        MethodVisitor own = cw.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        own.visitCode();
        own.visitVarInsn(ALOAD, 0);
        own.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        own.visitInsn(ARETURN);
        own.visitMaxs(0, 0);
        own.visitEnd();
        cw.visitEnd();

        assertEquals(List.of("com/retromod/shim/common/embedded/LegacyItemNames.toString", "java/lang/Object.toString"),
                callsFor(transformer, cw.toByteArray(), "1.20.1"));
        assertEquals(List.of("java/lang/Object.toString", "java/lang/Object.toString"),
                callsFor(transformer, cw.toByteArray(), "1.21"), "a 1.21 mod keeps toString");
    }

    private static List<String> callsFor(RetromodTransformer transformer, byte[] bytes, String sourceVersion) {
        transformer.setModSourceVersion(sourceVersion);
        try {
            return calls(transform(transformer, bytes, "mod/Recipe"));
        } finally {
            transformer.setModSourceVersion(null);
        }
    }

    private static ClassNode transform(RetromodTransformer transformer, byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, name)).accept(node, 0);
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

    private static List<String> fields(ClassNode node) {
        List<String> fields = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) fields.add(field.owner + "." + field.name);
            }
        }
        return fields;
    }
}
