/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * The register bridges run a mod's supplier with CURRENT holding the entry's key. A supplier that
 * throws must not leave the key behind, and one that registers an object itself must return it.
 */
class RegistryIdBridgeSupplierCallTest {

    private static final String OWNER = "test/SupplierBridge";

    @BeforeEach
    @AfterEach
    void clearSharedSlots() {
        System.getProperties().remove(RegistryIdBridgeSynthetic.SHARED_CURRENT_KEY);
        System.getProperties().remove(RegistryIdBridgeSynthetic.SHARED_HANDED_KEY);
    }

    @Test
    @DisplayName("the key is set while the supplier runs and cleared after")
    void keyIsScopedToTheSupplier() throws Exception {
        Class<?> bridge = bridge();
        Object[] seen = new Object[1];
        Object result = run(bridge, () -> {
            seen[0] = threadLocal(bridge, "CURRENT").get();
            return "entry";
        });

        assertEquals("entry", result);
        assertEquals("key", seen[0]);
        assertNull(threadLocal(bridge, "CURRENT").get());
    }

    @Test
    @DisplayName("a supplier that throws leaves neither the key nor a handed-back object behind")
    void aThrowingSupplierClearsBoth() throws Exception {
        Class<?> bridge = bridge();
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> run(bridge, () -> {
            threadLocal(bridge, "HANDED").set(new Object[] {"half registered"});
            throw new IllegalArgumentException("supplier failed");
        }));

        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
        assertNull(threadLocal(bridge, "CURRENT").get(), "a stale key would swallow a later registration");
        assertNull(threadLocal(bridge, "HANDED").get());
    }

    @Test
    @DisplayName("the object a supplier registered itself must be the one it returns")
    void aHandedBackObjectMustBeReturned() throws Exception {
        Class<?> bridge = bridge();
        assertEquals("tab", run(bridge, () -> {
            threadLocal(bridge, "HANDED").set(new Object[] {"tab", "tab"});
            return "tab";
        }));

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> run(bridge, () -> {
            threadLocal(bridge, "HANDED").set(new Object[] {"tab", "tab"});
            return "another tab";
        }));
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("key"), thrown.getCause().getMessage());
        assertNull(threadLocal(bridge, "HANDED").get());
    }

    @Test
    @DisplayName("a supplier may return the codec it passed in after the registration converted it to a map codec")
    void theOriginalOfAConvertedValueIsAccepted() throws Exception {
        Class<?> bridge = bridge();
        Object codec = new Object();
        Object mapCodec = new Object();
        // Registry.register(REG, id, CODEC); return CODEC; on a registry that holds map codecs.
        assertSame(codec, run(bridge, () -> {
            threadLocal(bridge, "HANDED").set(new Object[] {mapCodec, codec});
            return codec;
        }));
        // return Registry.register(REG, id, CODEC); hands back the converted map codec.
        assertSame(mapCodec, run(bridge, () -> {
            threadLocal(bridge, "HANDED").set(new Object[] {mapCodec, codec});
            return mapCodec;
        }));
    }

    @Test
    @DisplayName("a library's copy reads the addon copy's key, and hands its object back to that copy")
    void copiesInTwoJarsShareTheSlots() throws Exception {
        // Each class loader defines its own copy, as each translated jar embeds one.
        Class<?> addon = bridge();
        Class<?> library = bridge();
        assertNotSame(addon, library);
        Object[] seen = new Object[2];
        assertEquals("tab", run(addon, () -> {
            seen[0] = threadLocal(library, "CURRENT").get();
            Thread other = new Thread(() -> seen[1] = threadLocal(library, "CURRENT").get());
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            threadLocal(library, "HANDED").set(new Object[] {"tab"});
            return "tab";
        }));
        assertEquals("key", seen[0], "the library's copy sees the key on the supplier's thread");
        assertNull(seen[1], "another thread does not");
        assertNull(threadLocal(library, "CURRENT").get(), "the addon's copy cleared the shared key");

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> run(addon, () -> {
            threadLocal(library, "HANDED").set(new Object[] {"tab"});
            return "another tab";
        }));
        assertInstanceOf(IllegalStateException.class, thrown.getCause(), "the addon's copy checks what the library handed back");
        assertNull(threadLocal(library, "HANDED").get());
    }

    @Test
    @DisplayName("a copy keeps a slot of its own when the shared key holds something else")
    void foreignValueUnderTheKeyFallsBackToAPrivateSlot() throws Exception {
        System.getProperties().put(RegistryIdBridgeSynthetic.SHARED_CURRENT_KEY, "not a slot");
        Class<?> bridge = bridge();
        assertEquals("entry", run(bridge, () -> "entry"));
        assertNotNull(threadLocal(bridge, "CURRENT"));
        assertEquals("not a slot", System.getProperties().get(RegistryIdBridgeSynthetic.SHARED_CURRENT_KEY));
    }

    private static Object run(Class<?> bridge, Supplier<Object> supplier) throws Exception {
        Object instance = bridge.getConstructor(Supplier.class).newInstance(supplier);
        return bridge.getMethod("run", Object.class).invoke(instance, "key");
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Object> threadLocal(Class<?> bridge, String name) {
        try {
            Field field = bridge.getField(name);
            return (ThreadLocal<Object>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** A class with only the parts of a register bridge the shared emitters use. */
    private static Class<?> bridge() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, OWNER, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "CURRENT", "Ljava/lang/ThreadLocal;", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "HANDED", "Ljava/lang/ThreadLocal;", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "sup", "Ljava/util/function/Supplier;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        RegistryIdBridgeSynthetic.emitSharedSlots(init, OWNER);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/function/Supplier;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitFieldInsn(PUTFIELD, OWNER, "sup", "Ljava/util/function/Supplier;");
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        MethodVisitor run = cw.visitMethod(ACC_PUBLIC, "run", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        run.visitCode();
        RegistryIdBridgeSynthetic.emitSupplierCall(run, OWNER, 1, 2, 3);
        run.visitVarInsn(ALOAD, 2);
        run.visitInsn(ARETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        RegistryIdBridgeSynthetic.emitCheckHanded(cw, OWNER);
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        return new ClassLoader(RegistryIdBridgeSupplierCallTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
    }
}
