/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.forge.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * {@code DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, modid)}, as Scorched Guns builds
 * its enchantment register, on a host where enchantments come from datapacks.
 */
class LegacyDatapackRegistriesTest {

    @Test
    @DisplayName("The stand-in answers key() and explains every other call")
    void standInAnswersKeyOnly() throws Exception {
        FakeHost host = new FakeHost(getClass().getClassLoader());
        Class<?> helper = host.loadClass(LegacyDatapackRegistries.class.getName());
        Object registry = helper.getMethod("enchantments").invoke(null);
        Class<?> registryType = host.loadClass("net.minecraft.core.Registry");
        assertTrue(registryType.isInstance(registry), "DeferredRegister.create takes a Registry");

        Object key = registryType.getMethod("key").invoke(registry);
        assertEquals("minecraft:enchantment", key, "the register is built for the datapack registry's key");
        assertSame(registry, helper.getMethod("enchantments").invoke(null), "one stand-in per registry");

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> registryType.getMethod("size").invoke(registry));
        assertTrue(failure.getCause().getMessage().contains("datapacks"), failure.getCause().getMessage());
    }

    /** Defines a two-method Registry and a Registries holder, with the helper beside them. */
    private static final class FakeHost extends ClassLoader {
        private final Map<String, byte[]> classes;

        FakeHost(ClassLoader parent) throws Exception {
            super(parent);
            classes = Map.of(
                    "net.minecraft.core.Registry", registry(),
                    "net.minecraft.core.registries.Registries", registries(),
                    LegacyDatapackRegistries.class.getName(), resource(LegacyDatapackRegistries.class));
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                byte[] bytes = classes.get(name);
                if (bytes == null) return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
            }
        }

        private static byte[] registry() {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, "net/minecraft/core/Registry", null, "java/lang/Object", null);
            cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "key", "()Ljava/lang/Object;", null, null).visitEnd();
            cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "size", "()I", null, null).visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] registries() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "net/minecraft/core/registries/Registries", null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "ENCHANTMENT", "Ljava/lang/Object;", null, null).visitEnd();
            MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode();
            clinit.visitLdcInsn("minecraft:enchantment");
            clinit.visitFieldInsn(PUTSTATIC, "net/minecraft/core/registries/Registries", "ENCHANTMENT", "Ljava/lang/Object;");
            clinit.visitInsn(RETURN);
            clinit.visitMaxs(0, 0);
            clinit.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] resource(Class<?> type) throws Exception {
            try (InputStream in = type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
                return in.readAllBytes();
            }
        }
    }
}
