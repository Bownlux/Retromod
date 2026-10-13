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

    /**
     * Mythic Fantasies, a 1.19.2 Forge mod, on a NeoForge 1.21.1 server: its dimension's only
     * handler was client-only and removed, and NeoForge rejected the empty subscriber class.
     */
    @Test
    @DisplayName("a subscriber class with no handlers left loses the annotation")
    void noHandlersLeft() {
        assertFalse(annotated(read(NeoForgeAutoSubscriberGuard.apply(subscriber(false, false))).visibleAnnotations,
                AUTO), "NeoForge 21 rejects a subscriber class with nothing to register");
    }

    /** Spartan Shields, a 1.12.2 mod: its registry handlers carry a marker the 1.12 bridge converts later. */
    @Test
    @DisplayName("a subscriber whose handlers another pass subscribes keeps the annotation")
    void legacyHandlersAreLeftForTheirBridge() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/Registry1122", null, "java/lang/Object", null);
        cw.visitAnnotation(AUTO, true).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "(Ljava/lang/Object;)V", null, null);
        mv.visitAnnotation("Lcom/retromod/shim/forge/embedded/LegacySubscribeEvent;", true).visitEnd();
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        byte[] in = cw.toByteArray();
        assertSame(in, NeoForgeAutoSubscriberGuard.apply(in));
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

    @Test
    @DisplayName("on a NeoForge host, a handler for an event the host removed is dropped; the rest stay")
    void handlerForRemovedEventIsUnsubscribed() {
        String removed = "net/neoforged/neoforge/event/village/WandererTradesEvent";
        String present = "net/neoforged/neoforge/event/village/VillageSiegeEvent";
        String provided = "net/minecraftforge/event/server/ServerStartingEvent";
        String modOwn = "mymod/event/ManaEvent";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/Trades", null, "java/lang/Object", null);
        cw.visitAnnotation(AUTO, true).visitEnd();
        for (String event : List.of(removed, present, provided, modOwn)) {
            handler(cw, ACC_PUBLIC | ACC_STATIC, "on" + event.substring(event.lastIndexOf('/') + 1), "(L" + event + ";)V");
        }
        cw.visitEnd();

        ClassNode node = withHostClasses(List.of("net/neoforged/bus/api/Event", present),
                () -> read(NeoForgeAutoSubscriberGuard.apply(cw.toByteArray(), provided::equals)));

        assertEquals(List.of("onVillageSiegeEvent", "onServerStartingEvent", "onManaEvent"),
                node.methods.stream().map(m -> m.name).toList(),
                "NeoForge lists every method, so the removed event's handler must not remain");
        assertTrue(annotated(node.visibleAnnotations, AUTO), "the class stays an automatic subscriber");
    }

    @Test
    @DisplayName("without a visible host, such as an offline transform, no handler is dropped")
    void offlineKeepsEveryHandler() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/Trades", null, "java/lang/Object", null);
        cw.visitAnnotation(AUTO, true).visitEnd();
        handler(cw, ACC_PUBLIC | ACC_STATIC, "onTrades", "(Lnet/neoforged/neoforge/event/village/WandererTradesEvent;)V");
        cw.visitEnd();
        byte[] in = cw.toByteArray();
        assertSame(in, NeoForgeAutoSubscriberGuard.apply(in, name -> false));
    }

    /** Runs {@code body} with a context loader that answers the given class resources, as a host would. */
    private static <T> T withHostClasses(List<String> internalNames, java.util.function.Supplier<T> body) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader host = new ClassLoader(previous) {
            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                for (String internalName : internalNames) {
                    if (name.equals(internalName + ".class")) {
                        ClassWriter cw = new ClassWriter(0);
                        cw.visit(V17, ACC_PUBLIC, internalName, null, "java/lang/Object", null);
                        cw.visitEnd();
                        return new java.io.ByteArrayInputStream(cw.toByteArray());
                    }
                }
                return super.getResourceAsStream(name);
            }
        };
        Thread.currentThread().setContextClassLoader(host);
        try {
            return body.get();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
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
        handler(cw, access, name, "(Ljava/lang/Object;)V");
    }

    private static void handler(ClassWriter cw, int access, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(access, name, desc, null, null);
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
