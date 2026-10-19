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

    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String HELPER_DESC = "(Ljava/lang/Object;L" + MINECRAFT + ";)L" + MINECRAFT + ";";

    @Test
    @DisplayName("#283: a both-sides subscriber's private client helper no longer stops a server")
    void clientHelperSignatureIsErasedForServers() throws Exception {
        byte[] in = flightHandler(null);
        byte[] out = NeoForgeAutoSubscriberGuard.apply(in, name -> false);

        assertThrows(NoClassDefFoundError.class, () -> new Host(in, false).load().getDeclaredMethods(),
                "the original resolves Minecraft as NeoForge lists the class's methods on a server");
        assertEquals(3, new Host(out, false).load().getDeclaredMethods().length,
                "with the client type erased, a server can list every method");
        assertTrue(read(out).methods.stream().noneMatch(m -> m.desc.contains(MINECRAFT)));
    }

    @Test
    @DisplayName("the erased helper still takes and returns the client object on a client")
    void erasedHelperStillRunsOnAClient() throws Exception {
        Class<?> handler = new Host(NeoForgeAutoSubscriberGuard.apply(flightHandler(null), name -> false), true).load();
        Object minecraft = handler.getClassLoader().loadClass(MINECRAFT.replace('/', '.'))
                .getConstructor().newInstance();

        Object result = handler.getMethod("onTick", Object.class, Object.class).invoke(null, "player", minecraft);

        assertSame(minecraft, result, "the cast after the call hands the caller its Minecraft back");
    }

    @Test
    @DisplayName("a client-only subscriber is never listed on a server and is left alone")
    void clientOnlySubscriberIsLeftAlone() {
        byte[] in = flightHandler("CLIENT");
        assertTrue(read(NeoForgeAutoSubscriberGuard.apply(in, name -> false)).methods.stream()
                .anyMatch(m -> m.desc.equals(HELPER_DESC)));
    }

    /**
     * {@code @EventBusSubscriber class Flight}: a static handler, and a private helper
     * {@code static Minecraft movement(Object, Minecraft)} that the public {@code onTick} calls.
     */
    private static byte[] flightHandler(String dist) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/Flight", null, "java/lang/Object", null);
        org.objectweb.asm.AnnotationVisitor subscriber = cw.visitAnnotation(AUTO, true);
        subscriber.visit("modid", "scguns");
        if (dist != null) {
            org.objectweb.asm.AnnotationVisitor sides = subscriber.visitArray("value");
            sides.visitEnum(null, "Lnet/neoforged/api/distmarker/Dist;", dist);
            sides.visitEnd();
        }
        subscriber.visitEnd();
        handler(cw, ACC_PUBLIC | ACC_STATIC, "onPlayerTick");
        MethodVisitor tick = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onTick",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        tick.visitCode();
        tick.visitVarInsn(ALOAD, 0);
        tick.visitVarInsn(ALOAD, 1);
        tick.visitTypeInsn(CHECKCAST, MINECRAFT);
        tick.visitMethodInsn(INVOKESTATIC, "test/Flight", "movement", HELPER_DESC, false);
        tick.visitInsn(ARETURN);
        tick.visitMaxs(0, 0);
        tick.visitEnd();
        MethodVisitor helper = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "movement", HELPER_DESC, null, null);
        helper.visitCode();
        helper.visitVarInsn(ALOAD, 1);
        helper.visitInsn(ARETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Loads one class; a client host also has an empty {@code Minecraft}, a server has none. */
    private static final class Host extends ClassLoader {
        private final byte[] bytes;
        private final boolean client;

        Host(byte[] bytes, boolean client) {
            super(NeoForgeAutoSubscriberGuardTest.class.getClassLoader());
            this.bytes = bytes;
            this.client = client;
        }

        Class<?> load() throws ClassNotFoundException {
            return loadClass("test.Flight");
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            if (name.equals("test.Flight")) return defineClass(name, bytes, 0, bytes.length);
            if (name.equals(MINECRAFT.replace('/', '.'))) {
                if (!client) throw new ClassNotFoundException(name + " is client-only");
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                cw.visit(V17, ACC_PUBLIC, MINECRAFT, null, "java/lang/Object", null);
                MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
                init.visitCode();
                init.visitVarInsn(ALOAD, 0);
                init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                init.visitInsn(RETURN);
                init.visitMaxs(0, 0);
                init.visitEnd();
                cw.visitEnd();
                byte[] stub = cw.toByteArray();
                return defineClass(name, stub, 0, stub.length);
            }
            return super.loadClass(name, resolve);
        }
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
