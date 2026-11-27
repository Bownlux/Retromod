/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The pack supplier an old lambda is wrapped in opens packs by id, and a lambda that cannot link ends only its own pack. */
class LegacyPackSupplierLambdasTest {

    static final String METADATA = "net/minecraft/server/packs/repository/Pack$Metadata";

    @Test
    @DisplayName("both open methods answer with the old lambda's pack for the location's id")
    void opensByPackId() throws Exception {
        Map<String, Object> opened = new HashMap<>();
        Object supplier = supplier(id -> opened.computeIfAbsent(id, LegacyPackSupplierLambdasTest::resources));

        Object primary = open(supplier, "openPrimary", "tectonic/overlay");
        assertSame(opened.get("tectonic/overlay"), primary);
        assertSame(primary, open(supplier, "openFull", "tectonic/overlay"));
    }

    @Test
    @DisplayName("a lambda that throws NoClassDefFoundError gives the host an exception it catches, naming the pack")
    void linkageErrorsEndThePackOnly() throws Exception {
        Object supplier = supplier(id -> {
            throw new NoClassDefFoundError("net/minecraftforge/resource/PathPackResources");
        });

        for (String open : new String[]{"openPrimary", "openFull"}) {
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> open(supplier, open, "kiwi/pack"));
            IllegalStateException failed = assertInstanceOf(IllegalStateException.class, thrown.getCause(), open);
            assertInstanceOf(NoClassDefFoundError.class, failed.getCause());
            assertTrue(failed.getMessage().contains("kiwi/pack"), failed.getMessage());
        }
    }

    @Test
    @DisplayName("an exception from the lambda reaches the host unchanged, as before")
    void exceptionsPassThrough() throws Exception {
        Object supplier = supplier(id -> {
            throw new IllegalArgumentException("no such folder");
        });

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> open(supplier, "openPrimary", "a"));
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
    }

    /** The generated supplier over the function, loaded beside stand-ins for the host's pack types. */
    private static Object supplier(Function<String, Object> open) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(LegacyPackSupplierLambdas.RESOURCES, type(LegacyPackSupplierLambdas.RESOURCES));
        classes.put(METADATA, type(METADATA));
        classes.put(LegacyPackSupplierLambdas.SUPPLIER, supplierInterface());
        classes.put(LegacyPackSupplierLambdas.LOCATION, location());
        classes.put(LegacyPackSupplierLambdas.ADAPTER, LegacyPackSupplierLambdas.generateAdapter(LegacyPackSupplierLambdas.MOJANG, true));
        loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> adapter = loader.loadClass(LegacyPackSupplierLambdas.ADAPTER.replace('/', '.'));
        return adapter.getMethod("supplier", Function.class).invoke(null, open);
    }

    private static ClassLoader loader;

    private static Object open(Object supplier, String name, String id) throws Exception {
        Class<?> location = loader.loadClass(LegacyPackSupplierLambdas.LOCATION.replace('/', '.'));
        Object at = location.getConstructor(String.class).newInstance(id);
        Method method = name.equals("openPrimary") ? supplier.getClass().getMethod(name, location)
                : supplier.getClass().getMethod(name, location, loader.loadClass(METADATA.replace('/', '.')));
        return name.equals("openPrimary") ? method.invoke(supplier, at) : method.invoke(supplier, at, null);
    }

    /** A pack of the stand-in resources type, as the old lambda builds one. */
    private static Object resources(String id) {
        try {
            return loader.loadClass(LegacyPackSupplierLambdas.RESOURCES.replace('/', '.')).getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An empty public class with a public constructor. */
    private static byte[] type(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] supplierInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, LegacyPackSupplierLambdas.SUPPLIER, null,
                "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "openPrimary", LegacyPackSupplierLambdas.OPEN_PRIMARY, null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "openFull", LegacyPackSupplierLambdas.OPEN_FULL, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A pack location that holds its id. */
    private static byte[] location() {
        String name = LegacyPackSupplierLambdas.LOCATION;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "id", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, name, "id", "Ljava/lang/String;");
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        MethodVisitor id = cw.visitMethod(Opcodes.ACC_PUBLIC, "id", "()Ljava/lang/String;", null, null);
        id.visitCode();
        id.visitVarInsn(Opcodes.ALOAD, 0);
        id.visitFieldInsn(Opcodes.GETFIELD, name, "id", "Ljava/lang/String;");
        id.visitInsn(Opcodes.ARETURN);
        id.visitMaxs(0, 0);
        id.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
