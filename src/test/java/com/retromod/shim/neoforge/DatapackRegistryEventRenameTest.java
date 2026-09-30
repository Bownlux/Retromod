/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * NeoForge replaced {@code DataPackRegistryEvent.NewRegistry} with {@code NewDatapackRegistryEvent}
 * partway through the 26.3 betas, renaming {@code dataPackRegistry} to {@code worldRegistry} with
 * identical descriptors. Checked against 26.3.0.3-beta and 26.3.0.23-beta.
 */
class DatapackRegistryEventRenameTest {

    private static final String OLD = "net/neoforged/neoforge/registries/DataPackRegistryEvent$NewRegistry";
    private static final String NEW = "net/neoforged/neoforge/registries/NewDatapackRegistryEvent";
    private static final String DESC =
            "(Lnet/minecraft/resources/ResourceKey;Lcom/mojang/serialization/Codec;)V";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a listener for the old event registers against the new one and its new method")
    void listenerMovesToTheNewEvent() {
        NeoForge_26_2_to_26_3.registerDatapackRegistryEventRename(transformer);
        ClassNode out = read(transformer.transformClass(listener(), "test/mod/Registries"));

        MethodNode handler = out.methods.stream()
                .filter(m -> m.name.equals("onNewRegistry")).findFirst().orElseThrow();
        assertEquals("(L" + NEW + ";)V", handler.desc,
                "the bus picks the event type from the handler's parameter");

        MethodInsnNode call = null;
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof MethodInsnNode c) call = c;
        }
        assertNotNull(call);
        assertEquals(NEW, call.owner);
        assertEquals("worldRegistry", call.name);
        assertEquals(DESC, call.desc);
    }

    /** {@code @SubscribeEvent void onNewRegistry(NewRegistry e) { e.dataPackRegistry(key, codec); }} */
    private static byte[] listener() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Registries", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onNewRegistry",
                "(L" + OLD + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD, "dataPackRegistry", DESC, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    @Test
    @DisplayName("all three dataPackRegistry overloads become worldRegistry")
    void everyOverloadIsRenamed() {
        NeoForge_26_2_to_26_3.registerDatapackRegistryEventRename(transformer);
        String codec = "Lcom/mojang/serialization/Codec;";
        String key = "Lnet/minecraft/resources/ResourceKey;";
        for (String desc : new String[]{
                "(" + key + codec + ")V",
                "(" + key + codec + codec + ")V",
                "(" + key + codec + codec + "Ljava/util/function/Consumer;)V"}) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, "test/mod/Registries", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onNewRegistry",
                    "(L" + OLD + ";)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            int args = org.objectweb.asm.Type.getArgumentTypes(desc).length;
            for (int a = 0; a < args; a++) mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKEVIRTUAL, OLD, "dataPackRegistry", desc, false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();

            ClassNode out = read(transformer.transformClass(cw.toByteArray(), "test/mod/Registries"));
            MethodInsnNode call = null;
            for (AbstractInsnNode insn : out.methods.get(0).instructions) {
                if (insn instanceof MethodInsnNode c) call = c;
            }
            assertEquals("worldRegistry", call.name, desc);
            assertEquals(NEW, call.owner, desc);
        }
    }
}
