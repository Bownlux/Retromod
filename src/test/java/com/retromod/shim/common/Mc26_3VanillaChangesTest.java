/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.Arrays;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Vanilla changes in 26.3 that stopped Sophisticated Backpacks, which worked on 26.1.2: the
 * {@code PushReaction} constants were renamed, and {@code ResourceManager.listResources} took a
 * {@code Selector} in place of a {@code Predicate}.
 */
class Mc26_3VanillaChangesTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("PushReaction.DESTROY becomes POPPED and BLOCK becomes IMMOVEABLE")
    void pushReactionConstantsAreRenamed() {
        transformer.clearRedirectsForTesting();
        Mc26_2To26_3CoreMoves.registerPushReactionRenames(transformer);
        String owner = "net/minecraft/world/level/material/PushReaction";
        for (String[] pair : new String[][]{{"DESTROY", "POPPED"}, {"BLOCK", "IMMOVEABLE"}, {"NORMAL", "PUSH_PULL"}}) {
            MethodNode body = transform("()Ljava/lang/Object;", mv -> {
                mv.visitFieldInsn(GETSTATIC, owner, pair[0], "L" + owner + ";");
                mv.visitInsn(ARETURN);
            });
            assertEquals(pair[1], first(body, FieldInsnNode.class).name);
        }
    }

    @Test
    @DisplayName("an old listResources call goes through a selector wrapping the mod's predicate")
    void listResourcesTakesASelector() throws Exception {
        transformer.clearRedirectsForTesting();
        LegacyResourceSelectorBridge.register(transformer);
        String manager = LegacyResourceSelectorBridge.MANAGER;
        MethodNode body = transform("(L" + manager + ";Ljava/util/function/Predicate;)Ljava/util/Map;", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn("recipe");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, manager, "listResources", LegacyResourceSelectorBridge.OLD_DESC, true);
            mv.visitInsn(ARETURN);
        });
        MethodInsnNode call = first(body, MethodInsnNode.class);
        assertEquals(INVOKESTATIC, call.getOpcode());
        assertTrue(call.owner.endsWith("LegacyResourceSelector"), call.owner);

        ClassNode helper = new ClassNode();
        new ClassReader(LegacyResourceSelectorBridge.generateHelper()).accept(helper, 0);
        assertEquals(java.util.List.of(LegacyResourceSelectorBridge.SELECTOR), helper.interfaces);
        for (MethodNode method : helper.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
        }
    }

    private MethodNode transform(String desc, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/Caller")).accept(out, 0);
        return out.methods.stream().filter(m -> m.name.equals("call")).findFirst().orElseThrow();
    }

    private static <T extends AbstractInsnNode> T first(MethodNode method, Class<T> type) {
        return Arrays.stream(method.instructions.toArray()).filter(type::isInstance).map(type::cast)
                .findFirst().orElseThrow(() -> new AssertionError("no " + type.getSimpleName()));
    }
}
