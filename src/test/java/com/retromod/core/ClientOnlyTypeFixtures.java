/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Class shapes from a Forge or NeoForge server jar, and a way to run code on that server's side. */
final class ClientOnlyTypeFixtures {

    static final String FORGE_ONLY_IN = "Lnet/minecraftforge/api/distmarker/OnlyIn;";
    static final String FORGE_ONLY_INS = "Lnet/minecraftforge/api/distmarker/OnlyIns;";
    static final String FORGE_DIST = "Lnet/minecraftforge/api/distmarker/Dist;";
    static final String NEO_ONLY_IN = "Lnet/neoforged/api/distmarker/OnlyIn;";
    static final String NEO_DIST = "Lnet/neoforged/api/distmarker/Dist;";

    static final String LEVEL = "net/minecraft/world/level/Level";
    static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
    static final String PLAYER = "net/minecraft/world/entity/player/Player";
    static final String ABSTRACT_CLIENT_PLAYER = "net/minecraft/client/player/AbstractClientPlayer";
    static final String LOCAL_PLAYER = "net/minecraft/client/player/LocalPlayer";
    static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    static final String MOB_MIXIN = "com/example/morehitboxes/mixin/ClientMobMixin";

    /** What the server jar carries: client classes marked as Forge 47 and NeoForge 21.1 mark them. */
    static final Map<String, byte[]> SERVER_JAR = Map.of(
            LEVEL, type(LEVEL, "java/lang/Object", cv -> {}),
            CLIENT_LEVEL, type(CLIENT_LEVEL, LEVEL, onlyIn(NEO_ONLY_IN, NEO_DIST, "CLIENT")),
            PLAYER, type(PLAYER, "java/lang/Object", cv -> {}),
            ABSTRACT_CLIENT_PLAYER, type(ABSTRACT_CLIENT_PLAYER, PLAYER,
                    onlyIn(FORGE_ONLY_IN, FORGE_DIST, "CLIENT")),
            LOCAL_PLAYER, type(LOCAL_PLAYER, ABSTRACT_CLIENT_PLAYER, cv -> {}),
            SERVER_PLAYER, type(SERVER_PLAYER, PLAYER, cv -> {}));

    private ClientOnlyTypeFixtures() {}

    /** A class with {@code annotate} applied to its header. */
    static byte[] type(String name, String superName, Consumer<ClassWriter> annotate) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        annotate.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code @OnlyIn(value)} as the dist cleaner reads it: visible at runtime. */
    static Consumer<ClassWriter> onlyIn(String marker, String dist, String side) {
        return cw -> {
            AnnotationVisitor av = cw.visitAnnotation(marker, true);
            av.visitEnum("value", dist, side);
            av.visitEnd();
        };
    }

    /** {@code @OnlyIn(value = CLIENT, _interface = iface)}, which strips one interface instead. */
    static Consumer<ClassWriter> onlyInInterface(String iface) {
        return cw -> {
            AnnotationVisitor av = cw.visitAnnotation(FORGE_ONLY_IN, true);
            av.visitEnum("value", FORGE_DIST, "CLIENT");
            av.visit("_interface", Type.getObjectType(iface));
            av.visitEnd();
        };
    }

    /** {@code @OnlyIns({@OnlyIn(CLIENT)})}, the repeated form. */
    static Consumer<ClassWriter> onlyIns() {
        return cw -> {
            AnnotationVisitor outer = cw.visitAnnotation(FORGE_ONLY_INS, true);
            AnnotationVisitor values = outer.visitArray("value");
            AnnotationVisitor inner = values.visitAnnotation(null, FORGE_ONLY_IN);
            inner.visitEnum("value", FORGE_DIST, "CLIENT");
            inner.visitEnd();
            values.visitEnd();
            outer.visitEnd();
        };
    }

    /** The class structure, or null when there are no bytes, as a resource read returns it. */
    static ClassNode node(byte[] bytes) {
        if (bytes == null) return null;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        return node;
    }

    /** Runs {@code body} on the given side with {@code jar} as the context loader, then restores both. */
    static <T> T onSide(boolean dedicatedServer, ServerJarLoader jar, Callable<T> body) throws Exception {
        Field[] fields = {
                EnvironmentDetector.class.getDeclaredField("isClient"),
                EnvironmentDetector.class.getDeclaredField("isHeadless"),
                EnvironmentDetector.class.getDeclaredField("isDedicatedServer")};
        Object[] saved = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            fields[i].setAccessible(true);
            saved[i] = fields[i].get(null);
        }
        Thread thread = Thread.currentThread();
        ClassLoader savedContext = thread.getContextClassLoader();
        EnvironmentDetector.setLoaderEnvironment(!dedicatedServer);
        thread.setContextClassLoader(jar);
        // Refusals are cached by name for the JVM, so no other test's answer may leak in or out.
        RetromodTransformer.forgetRefusedTypesForTesting();
        try {
            return body.call();
        } finally {
            RetromodTransformer.forgetRefusedTypesForTesting();
            thread.setContextClassLoader(savedContext);
            for (int i = 0; i < fields.length; i++) {
                fields[i].set(null, saved[i]);
            }
        }
    }

    /** Serves {@link #SERVER_JAR} as class resources and records every class it is asked to load. */
    static final class ServerJarLoader extends ClassLoader {
        final List<String> requested = new CopyOnWriteArrayList<>();

        ServerJarLoader() {
            super(ClientOnlyTypeFixtures.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            requested.add(name.replace('.', '/'));
            return super.loadClass(name, resolve);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            byte[] bytes = name.endsWith(".class")
                    ? SERVER_JAR.get(name.substring(0, name.length() - ".class".length())) : null;
            return bytes != null ? new ByteArrayInputStream(bytes) : super.getResourceAsStream(name);
        }
    }
}
