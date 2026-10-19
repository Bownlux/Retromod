/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #283: Scorched Guns builds its stairs with Forge's {@code new StairBlock(Supplier, Properties)},
 * which NeoForge 1.21 removed. The rewritten call reads the supplier and passes the state.
 */
class LegacyStairBlockBridgeTest {

    private static final String STAIR = LegacyStairBlockBridge.STAIR;
    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("new StairBlock(supplier, properties) gets the supplied state")
    void plainStairGetsTheSuppliedState() throws Exception {
        Stubs host = registerOnModernHost();
        Object state = host.load(STATE).getConstructor().newInstance();
        Object properties = host.load(PROPERTIES).getConstructor().newInstance();

        Object stair = host.load("test/ModStairs").getMethod("stairs", Supplier.class, host.load(PROPERTIES))
                .invoke(null, (Supplier<Object>) () -> state, properties);

        assertArrayEquals(new Object[] {state, properties}, args(stair));
    }

    @Test
    @DisplayName("a subclass's super(supplier, properties) gets the supplied state too")
    void subclassStairGetsTheSuppliedState() throws Exception {
        Stubs host = registerOnModernHost();
        Object state = host.load(STATE).getConstructor().newInstance();
        Object properties = host.load(PROPERTIES).getConstructor().newInstance();

        Object stair = host.load("test/ModStair").getConstructor(Supplier.class, host.load(PROPERTIES))
                .newInstance((Supplier<Object>) () -> state, properties);

        assertArrayEquals(new Object[] {state, properties}, args(stair));
    }

    @Test
    @DisplayName("a host that still has Forge's supplier constructor gets no rewrite")
    void forgeHostKeepsItsConstructor() {
        ClassNode forgeStair = node(argsHolder(STAIR, "(Ljava/util/function/Supplier;L" + PROPERTIES + ";)V"));
        assertFalse(LegacyStairBlockBridge.register(transformer, forgeStair));
    }

    private Stubs registerOnModernHost() {
        byte[] stair = argsHolder(STAIR, "(L" + STATE + ";L" + PROPERTIES + ";)V");
        assertTrue(LegacyStairBlockBridge.register(transformer, node(stair)));
        Stubs host = new Stubs();
        host.add(STAIR, stair);
        host.add(STATE, plainClass(STATE, "java/lang/Object"));
        host.add(PROPERTIES, plainClass(PROPERTIES, "java/lang/Object"));
        host.add("test/ModStairs", transformer.transformClass(modStairs(), "test/ModStairs.class"));
        host.add("test/ModStair", transformer.transformClass(modStair(), "test/ModStair.class"));
        host.add(Forge1122BlockConstructorAdapter.STATES, resource(Forge1122BlockConstructorAdapter.STATES));
        return host;
    }

    /** {@code static Object stairs(Supplier s, Properties p) { return new StairBlock(s, p); }} */
    private static byte[] modStairs() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/ModStairs", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "stairs",
                "(Ljava/util/function/Supplier;L" + PROPERTIES + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, STAIR);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, STAIR, "<init>", LegacyStairBlockBridge.FORGE_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class ModStair extends StairBlock { ModStair(Supplier s, Properties p) { super(s, p); } }} */
    private static byte[] modStair() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/ModStair", null, STAIR, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", LegacyStairBlockBridge.FORGE_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, STAIR, "<init>", LegacyStairBlockBridge.FORGE_DESC, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class whose one public constructor keeps its arguments in {@code args}. */
    private static byte[] argsHolder(String name, String ctor) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC, "args", "[Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", ctor, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_2);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        for (int i = 0; i < 2; i++) {
            mv.visitInsn(DUP);
            mv.visitLdcInsn(i);
            mv.visitVarInsn(ALOAD, i + 1);
            mv.visitInsn(AASTORE);
        }
        mv.visitFieldInsn(PUTFIELD, name, "args", "[Ljava/lang/Object;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] plainClass(String name, String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Object[] args(Object holder) throws Exception {
        return (Object[]) holder.getClass().getField("args").get(holder);
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        return node;
    }

    private static byte[] resource(String internalName) {
        try (InputStream in = LegacyStairBlockBridgeTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new AssertionError("missing " + internalName, e);
        }
    }

    /** Defines the stand-ins and the transformed mod in isolation from the test classpath. */
    private static final class Stubs extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();
        private final Map<String, Class<?>> defined = new HashMap<>();

        Stubs() {
            super(LegacyStairBlockBridgeTest.class.getClassLoader());
        }

        void add(String internalName, byte[] bytes) {
            classes.put(internalName.replace('/', '.'), bytes);
        }

        Class<?> load(String internalName) throws ClassNotFoundException {
            return loadClass(internalName.replace('/', '.'));
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) return super.loadClass(name, resolve);
            Class<?> type = defined.get(name);
            if (type == null) {
                // Defining a class loads its superclass through here, so no computeIfAbsent.
                type = defineClass(name, bytes, 0, bytes.length);
                defined.put(name, type);
            }
            return type;
        }
    }
}
