/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #283: Scorched Guns registers {@code new PaintingVariant(width, height)} in pixels. 1.21 kept the
 * variant in its old package until 26.1 but made it a record with an asset id and sizes in blocks,
 * so the bridge has to apply on 1.21 to 1.21.11 hosts as well as on 26.x.
 */
class LegacyPaintingVariantBridgeTest {

    private static final String VARIANT = LegacyPaintingVariantBridge.VARIANT_1_21;
    private static final String ID = "net/minecraft/resources/ResourceLocation";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a 1.21.1 host builds the old pixel-sized painting with sizes in blocks")
    void oldPaintingOnA1211Host() throws Exception {
        byte[] variant = variant1211();
        assertTrue(LegacyPaintingVariantBridge.register(transformer, VARIANT, node(variant)));

        // Only the 1.21 class exists here, so the helper must fall back from the 26.1 package.
        Host host = new Host();
        host.add(VARIANT, variant);
        host.add(ID, resourceLocation());
        host.add(LegacyPaintingVariantBridge.CALLS, transformer.getSyntheticClasses().get(LegacyPaintingVariantBridge.CALLS));
        host.add(LegacyPaintingVariantBridge.HELPER, resource(LegacyPaintingVariantBridge.HELPER));
        host.add("test/ModPaintings", transformer.transformClass(modPaintings(), "test/ModPaintings.class"));

        Object painting = host.loadClass("test.ModPaintings").getMethod("collective").invoke(null);
        Object[] args = (Object[]) painting.getClass().getField("args").get(painting);

        assertEquals(VARIANT.replace('/', '.'), painting.getClass().getName());
        assertEquals(2, args[0], "32 pixels are two blocks");
        assertEquals(1, args[1], "16 pixels are one block");
        assertEquals(ID.replace('/', '.'), args[2].getClass().getName());
    }

    @Test
    @DisplayName("a host that still has the pixel constructor gets no redirect")
    void oldHostIsLeftAlone() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, VARIANT, null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC, "<init>", "(II)V", null, null).visitEnd();
        cw.visitEnd();
        assertFalse(LegacyPaintingVariantBridge.register(transformer, VARIANT, node(cw.toByteArray())));
    }

    /** {@code static Object collective() { return new PaintingVariant(32, 16); }} */
    private static byte[] modPaintings() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/ModPaintings", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "collective", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, VARIANT);
        mv.visitInsn(DUP);
        mv.visitIntInsn(BIPUSH, 32);
        mv.visitIntInsn(BIPUSH, 16);
        mv.visitMethodInsn(INVOKESPECIAL, VARIANT, "<init>", "(II)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The 1.21.1 record shape: {@code PaintingVariant(int, int, ResourceLocation)}, kept in {@code args}. */
    private static byte[] variant1211() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, VARIANT, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC, "args", "[Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(IIL" + ID + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_3);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        for (int i = 0; i < 2; i++) {
            mv.visitInsn(DUP);
            mv.visitLdcInsn(i);
            mv.visitVarInsn(ILOAD, i + 1);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            mv.visitInsn(AASTORE);
        }
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitInsn(AASTORE);
        mv.visitFieldInsn(PUTFIELD, VARIANT, "args", "[Ljava/lang/Object;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code ResourceLocation.parse(String)}, which the helper uses for the placeholder asset id. */
    private static byte[] resourceLocation() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, ID, null, "java/lang/Object", null);
        HostStubs.defaultConstructor(cw, "java/lang/Object");
        MethodVisitor parse = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "parse", "(Ljava/lang/String;)L" + ID + ";", null, null);
        parse.visitCode();
        parse.visitTypeInsn(NEW, ID);
        parse.visitInsn(DUP);
        parse.visitMethodInsn(INVOKESPECIAL, ID, "<init>", "()V", false);
        parse.visitInsn(ARETURN);
        parse.visitMaxs(0, 0);
        parse.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Defines only the classes given, so a class the host lacks stays missing. */
    private static final class Host extends ClassLoader {
        private final java.util.Map<String, byte[]> classes = new java.util.HashMap<>();
        private final java.util.Map<String, Class<?>> defined = new java.util.HashMap<>();

        Host() {
            super(LegacyPaintingVariantBridgeTest.class.getClassLoader());
        }

        void add(String internalName, byte[] bytes) {
            classes.put(internalName.replace('/', '.'), bytes);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                if (name.startsWith("net.minecraft.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
            Class<?> type = defined.get(name);
            if (type == null) {
                type = defineClass(name, bytes, 0, bytes.length);
                defined.put(name, type);
            }
            return type;
        }
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        return node;
    }

    private static byte[] resource(String internalName) {
        try (InputStream in = LegacyPaintingVariantBridgeTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new AssertionError("missing " + internalName, e);
        }
    }
}
