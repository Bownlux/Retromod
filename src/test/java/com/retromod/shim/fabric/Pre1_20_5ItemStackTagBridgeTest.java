/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pre-1.20.5 mods read and write an item's NBT through {@code getTag()}, {@code getOrCreateTag()},
 * {@code setTag()} and {@code hasTag()}. These tests run the generated adapters against stand-ins
 * of the 1.21.1 custom data component and pin the call rewrite.
 */
class Pre1_20_5ItemStackTagBridgeTest {

    private static final String STUBS = "com/retromod/shim/fabric/tagstub/TagStubs$";
    private static final Map<String, String> HOST_NAMES = Map.of(
            STUBS + "CompoundTag", "net/minecraft/class_2487",
            STUBS + "ComponentType", "net/minecraft/class_9331",
            STUBS + "DataComponents", "net/minecraft/class_9334",
            STUBS + "CustomData", "net/minecraft/class_9279",
            STUBS + "ItemStack", "net/minecraft/class_1799",
            STUBS + "Enchantment", "net/minecraft/class_1887");

    private RetromodTransformer transformer;
    private StubLoader loader;
    private Class<?> helper;

    @BeforeEach
    void setUp() throws Exception {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        loader = new StubLoader();
        helper = loader.define(Pre1_20_5ItemStackTagBridge.HELPER, Pre1_20_5ItemStackTagBridge.generateHelper());
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    void getOrCreateTagHandsOutATagThatWritesThrough() throws Exception {
        Object stack = newStack();
        assertFalse((boolean) call("method_7985", stack), "a fresh stack has no tag");
        assertNull(call("method_7969", stack), "getTag on a fresh stack is null, as before");

        values(call("method_7948", stack)).put("Charge", 3);

        assertTrue((boolean) call("method_7985", stack));
        assertEquals(3, values(call("method_7969", stack)).get("Charge"),
                "a change to the created tag must be visible through later reads");
    }

    @Test
    void aCopiedStackKeepsItsOwnTag() throws Exception {
        Object original = newStack();
        values(call("method_7948", original)).put("Owner", "a");
        Object copy = original.getClass().getMethod("copy").invoke(original);

        values(call("method_7969", copy)).put("Owner", "b");

        assertEquals("a", values(call("method_7969", original)).get("Owner"),
                "the copy shares the component instance, so writing through it must not reach the original");
    }

    @Test
    void setTagReplacesAndNullRemoves() throws Exception {
        Object stack = newStack();
        Object tag = loader.loadClass("net.minecraft.class_2487").getConstructor().newInstance();
        values(tag).put("Mode", 1);

        call("method_7980", stack, tag);
        values(tag).put("Mode", 2);
        assertEquals(1, values(call("method_7969", stack)).get("Mode"),
                "the stack stores a copy, so later changes to the caller's tag do not leak in");

        call("method_7980", stack, null);
        assertFalse((boolean) call("method_7985", stack));
        assertNull(call("method_7969", stack));
    }

    @Test
    void enchantingWithABareEnchantmentExplainsWhyItCannot() throws Exception {
        Method enchant = method("method_7978");
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> enchant.invoke(null, newStack(), null, 1));
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("data pack"), failure.getCause().getMessage());
    }

    @Test
    void oldAccessorCallsBecomeStaticAdapterCalls() {
        Pre1_20_5ItemStackTagBridge.registerRedirects(transformer);

        byte[] out = transformer.transformClass(tagReader(), "test/TagReader");

        MethodInsnNode call = firstCall(out);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals(Pre1_20_5ItemStackTagBridge.HELPER, call.owner);
        assertEquals(Pre1_20_5ItemStackTagBridge.GET_TAG.adapterDesc(), call.desc);
        assertTrue(transformer.getSyntheticClasses().containsKey(Pre1_20_5ItemStackTagBridge.HELPER),
                "the adapters must travel with the mod");
    }

    private Object newStack() throws Exception {
        return loader.loadClass("net.minecraft.class_1799").getConstructor().newInstance();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(Object tag) throws Exception {
        return (Map<String, Object>) tag.getClass().getField("values").get(tag);
    }

    private Object call(String name, Object... args) throws Exception {
        return method(name).invoke(null, args);
    }

    private Method method(String name) {
        for (Method method : helper.getMethods()) {
            if (method.getName().equals(name)) return method;
        }
        throw new AssertionError("the helper has no adapter " + name);
    }

    /** {@code CompoundTag read(ItemStack stack) { return stack.getTag(); }} */
    private static byte[] tagReader() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/TagReader", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read",
                "(Lnet/minecraft/class_1799;)Lnet/minecraft/class_2487;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Pre1_20_5ItemStackTagBridge.ITEM_STACK,
                Pre1_20_5ItemStackTagBridge.GET_TAG.name(), Pre1_20_5ItemStackTagBridge.GET_TAG.oldDesc(), false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode firstCall(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) return call;
            }
        }
        throw new AssertionError("no call in the transformed class");
    }

    /** Loads the stand-ins under their host names and the generated helper beside them. */
    private static final class StubLoader extends ClassLoader {
        private final Map<String, String> stubByHost = new HashMap<>();

        StubLoader() {
            super(Pre1_20_5ItemStackTagBridgeTest.class.getClassLoader());
            HOST_NAMES.forEach((stub, host) -> stubByHost.put(host.replace('/', '.'), stub));
        }

        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                String stub = stubByHost.get(name);
                if (stub == null) return super.loadClass(name, resolve);
                try (InputStream in = getParent().getResourceAsStream(stub + ".class")) {
                    ClassWriter writer = new ClassWriter(0);
                    new ClassReader(in.readAllBytes()).accept(
                            new ClassRemapper(writer, new SimpleRemapper(HOST_NAMES)), 0);
                    return define(name.replace('.', '/'), writer.toByteArray());
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }
}
