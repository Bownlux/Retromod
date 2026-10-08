/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Macaw's mods put {@code @Mod.EventBusSubscriber} on their main class and register its instance
 * handlers with {@code register(this)}. Forge auto-subscribed only static handlers, so this worked;
 * NeoForge rejects the class at construction with "annotated with @SubscribeEvent is not static".
 */
class NeoForgeAutoSubscriberGuardTest {

    private static final String AUTO = "Lnet/neoforged/fml/common/EventBusSubscriber;";
    private static final String SUBSCRIBE = "Lnet/neoforged/bus/api/SubscribeEvent;";

    @Test
    @DisplayName("a subscriber class whose handlers are all instance methods loses the annotation")
    void instanceHandlersOnly() {
        byte[] out = NeoForgeAutoSubscriberGuard.apply(subscriber(false, true));
        ClassNode node = read(out);
        assertFalse(annotated(node.visibleAnnotations, AUTO), "NeoForge must not auto-subscribe it");
        assertTrue(node.methods.stream().anyMatch(m -> annotated(m.visibleAnnotations, SUBSCRIBE)),
                "the instance handler keeps @SubscribeEvent for the mod's own register(this)");
    }

    @Test
    @DisplayName("a subscriber class with only static handlers is returned unchanged")
    void staticHandlersOnly() {
        byte[] in = subscriber(true, false);
        assertSame(in, NeoForgeAutoSubscriberGuard.apply(in));
    }

    @Test
    @DisplayName("a class with both kinds loses the annotation instead of failing the whole mod")
    void mixedHandlers() {
        assertFalse(annotated(read(NeoForgeAutoSubscriberGuard.apply(subscriber(true, true))).visibleAnnotations,
                AUTO));
    }

    @Test
    @DisplayName("a class without the annotation is returned unchanged")
    void notASubscriber() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, "test/Plain", null, "java/lang/Object", null);
        cw.visitEnd();
        byte[] in = cw.toByteArray();
        assertSame(in, NeoForgeAutoSubscriberGuard.apply(in));
    }

    private static byte[] subscriber(boolean staticHandler, boolean instanceHandler) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/ModMain", null, "java/lang/Object", null);
        cw.visitAnnotation(AUTO, true).visitEnd();
        if (staticHandler) handler(cw, ACC_PUBLIC | ACC_STATIC, "onRegisterCommands");
        if (instanceHandler) handler(cw, ACC_PUBLIC, "onServerStarting");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void handler(ClassWriter cw, int access, String name) {
        MethodVisitor mv = cw.visitMethod(access, name, "(Ljava/lang/Object;)V", null, null);
        mv.visitAnnotation(SUBSCRIBE, true).visitEnd();
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, (access & ACC_STATIC) != 0 ? 1 : 2);
        mv.visitEnd();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static boolean annotated(List<AnnotationNode> annotations, String desc) {
        return annotations != null && annotations.stream().anyMatch(a -> desc.equals(a.desc));
    }
}
