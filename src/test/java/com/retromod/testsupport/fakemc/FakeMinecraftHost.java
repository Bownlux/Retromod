/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc;

import com.retromod.polyfill.minecraft.RetroEntityTypeBuild;
import com.retromod.shim.forge.RegistryIdBridgeSynthetic;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * One mod's view of a 1.21.2 or newer host, in its own class loader: the stand-ins renamed into
 * Minecraft's packages, Retromod's entity type build bridge under the prefix the embedder
 * relocates it to, a deferred register bridge's id slot beside it, and any mod classes a test
 * adds. Only the JDK is visible past it, so nothing resolves against the test's own classpath.
 */
public final class FakeMinecraftHost {

    private static final String BRIDGE = "com/retromod/polyfill/minecraft/RetroEntityTypeBuild";

    private final String base;
    private final String idClass;
    private final ClassLoader loader;

    /**
     * @param idClass    {@code net/minecraft/resources/Identifier}, or {@code ResourceLocation}
     *                   for a host before 1.21.11
     * @param base       the embedder's relocation prefix, or "" for the bridge at its own name
     * @param modClasses internal name to bytes of the mod classes to load beside them
     */
    public FakeMinecraftHost(String idClass, String base, Map<String, byte[]> modClasses) throws IOException {
        this.base = base;
        this.idClass = idClass;
        Map<String, String> names = new HashMap<>();
        names.put(internal(FakeIdentifier.class), idClass);
        names.put(internal(FakeResourceKey.class), "net/minecraft/resources/ResourceKey");
        names.put(internal(FakeRegistries.class), "net/minecraft/core/registries/Registries");
        names.put(internal(FakeRegistry.class), "net/minecraft/core/Registry");
        names.put(internal(FakeBuiltInRegistries.class), "net/minecraft/core/registries/BuiltInRegistries");
        names.put(internal(FakeEntityType.class), "net/minecraft/world/entity/EntityType");
        names.put(internal(FakeEntityTypeBuilder.class), "net/minecraft/world/entity/EntityType$Builder");
        names.put(BRIDGE, base + BRIDGE);
        SimpleRemapper remapper = new SimpleRemapper(names);

        Map<String, byte[]> classes = new HashMap<>();
        for (Class<?> type : new Class<?>[]{FakeIdentifier.class, FakeResourceKey.class, FakeRegistries.class,
                FakeRegistry.class, FakeBuiltInRegistries.class, FakeEntityType.class,
                FakeEntityTypeBuilder.class, RetroEntityTypeBuild.class}) {
            classes.put(names.get(internal(type)).replace('/', '.'), remap(bytesOf(type), remapper));
        }
        String slot = base + RegistryIdBridgeSynthetic.INTERNAL;
        classes.put(slot.replace('/', '.'), registrationSlot(slot));
        modClasses.forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    public Class<?> type(String internalName) throws ClassNotFoundException {
        return Class.forName(internalName.replace('/', '.'), true, loader);
    }

    public Object id(String text) throws Exception {
        return type(idClass).getMethod("parse", String.class).invoke(null, text);
    }

    public Object registry(String field) throws Exception {
        return type("net/minecraft/core/registries/Registries").getField(field).get(null);
    }

    public Object key(Object registry, String id) throws Exception {
        Class<?> keyClass = type("net/minecraft/resources/ResourceKey");
        return keyClass.getMethod("create", keyClass, type(idClass)).invoke(null, registry, id(id));
    }

    public Object builder() throws Exception {
        return type("net/minecraft/world/entity/EntityType$Builder").getConstructor().newInstance();
    }

    /** The bridge class, under the prefix this host relocated it to. */
    public Class<?> bridge() throws ClassNotFoundException {
        return type(base + BRIDGE);
    }

    /** Sets what the deferred register bridge holds while a registration supplier runs. */
    @SuppressWarnings("unchecked")
    public void setRegistration(Object current) throws Exception {
        ((ThreadLocal<Object>) type(base + RegistryIdBridgeSynthetic.INTERNAL).getField("CURRENT").get(null))
                .set(current);
    }

    /** {@code public final class <name> { public static final ThreadLocal CURRENT = new ThreadLocal(); }} */
    private static byte[] registrationSlot(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CURRENT",
                "Ljava/lang/ThreadLocal;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitTypeInsn(Opcodes.NEW, "java/lang/ThreadLocal");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/ThreadLocal", "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, name, "CURRENT", "Ljava/lang/ThreadLocal;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        try (InputStream in = type.getClassLoader().getResourceAsStream(internal(type) + ".class")) {
            return in.readAllBytes();
        }
    }

    private static byte[] remap(byte[] bytes, SimpleRemapper remapper) {
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(cw, remapper), 0);
        return cw.toByteArray();
    }
}
