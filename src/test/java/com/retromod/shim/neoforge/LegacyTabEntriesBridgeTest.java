/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A Forge 1.20.1 creative tab listener's entry map reaches the rebuilt view over NeoForge's event. */
class LegacyTabEntriesBridgeTest {

    private static final String MOD_CLASS = "test/tabs/Tabs";
    private static final String MAP = LegacyTabEntriesBridge.FORGE_MAP;
    private static final String ENTRIES = LegacyTabEntriesBridge.ENTRIES;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void reset() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.3";
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("getEntries, putAfter, put and iterator reach the rebuilt view, which the jar then carries")
    void listenerReachesTheView() {
        LegacyTabEntriesBridge.register(transformer);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build",
                "(L" + LegacyTabEntriesBridge.NEO_EVENT + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyTabEntriesBridge.NEO_EVENT, "getEntries", "()L" + MAP + ";", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MAP, "putAfter", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MAP, "iterator", "()Ljava/util/Iterator;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), MOD_CLASS)).accept(node, 0);

        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        // The map calls stay virtual calls on NeoForge's map, which the view overrides.
        String neoMap = LegacyTabEntriesBridge.NEO_MAP;
        assertEquals(List.of(ENTRIES + ".of(L" + LegacyTabEntriesBridge.NEO_EVENT + ";)L" + neoMap + ";",
                neoMap + ".putAfter(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                neoMap + ".iterator()Ljava/util/Iterator;"), calls);
        assertTrue(transformer.getSyntheticClasses().containsKey(ENTRIES));
    }

    @Test
    @DisplayName("A map a mod builds itself becomes NeoForge's map, not the tab view")
    void ownMapKeepsNeoForgesClass() {
        LegacyTabEntriesBridge.register(transformer);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, MAP);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MAP, "<init>", "()V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), MOD_CLASS)).accept(node, 0);

        List<String> owners = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) owners.add(call.owner + "." + call.name);
            }
        }
        assertEquals(List.of(LegacyTabEntriesBridge.NEO_MAP + ".<init>"), owners);
    }

    @Test
    @DisplayName("The rebuilt view extends NeoForge's map and overrides every member the corpus calls")
    void viewHasForgesMembers() throws Exception {
        ClassNode view = new ClassNode();
        try (var in = getClass().getClassLoader().getResourceAsStream(ENTRIES + ".class")) {
            assertNotNull(in, "the rebuilt view is in Retromod's jar");
            new ClassReader(in.readAllBytes()).accept(view, ClassReader.SKIP_CODE);
        }
        assertEquals(LegacyTabEntriesBridge.NEO_MAP, view.superName);
        List<String> members = new ArrayList<>();
        for (MethodNode method : view.methods) {
            if ((method.access & Opcodes.ACC_PUBLIC) != 0) members.add(method.name + method.desc);
        }
        String object = "Ljava/lang/Object;";
        for (String member : List.of("put(" + object + object + ")" + object, "putFirst(" + object + object + ")" + object,
                "putAfter(" + object + object + object + ")" + object, "putBefore(" + object + object + object + ")" + object,
                "remove(" + object + ")" + object, "contains(" + object + ")Z", "isEmpty()Z", "iterator()Ljava/util/Iterator;")) {
            assertTrue(members.contains(member), member + " in " + members);
        }
    }
}
