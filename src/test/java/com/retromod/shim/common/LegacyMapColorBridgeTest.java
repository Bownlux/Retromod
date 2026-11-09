/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.forge.Forge_1_21_1_to_1_21_2;
import com.retromod.shim.neoforge.NeoForge_1_21_1_to_1_21_2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Map;

import static com.retromod.shim.common.LegacyMapColorBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.21.1 mod reading a map color as ABGR, against the 1.21.2 ARGB method. */
class LegacyMapColorBridgeTest {

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousHost;
    }

    @Test
    void theOldColorCallGoesThroughTheHelper() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer);

        MethodInsnNode call = lastCall(transformer.transformClass(legacyCaller(), "test/MapTint.class"));
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(), "the helper is static");
        assertEquals(HELPER + "." + OLD_NAME + HELPER_DESC, call.owner + "." + call.name + call.desc);
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER), "the helper ships with the mod");
    }

    @Test
    void theHelperReturnsTheOldAbgrValue() throws Exception {
        Class<?> brightness = defineStubs().loadClass(MAP_COLOR.replace('/', '.') + "$Brightness");
        ClassLoader loader = brightness.getClassLoader();
        Class<?> mapColor = loader.loadClass(MAP_COLOR.replace('/', '.'));
        var helper = loader.loadClass(HELPER.replace('/', '.')).getMethod(OLD_NAME, mapColor, brightness);

        Object red = mapColor.getConstructor(int.class).newInstance(0xFF112233);
        assertEquals(0xFF332211, (int) helper.invoke(null, red, null), "red and blue trade places, alpha and green stay");
        Object none = mapColor.getConstructor(int.class).newInstance(0);
        assertEquals(0, (int) helper.invoke(null, none, null), "MapColor.NONE stays 0");
    }

    @Test
    void offlineTheHostVersionDecides() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertFalse(hostReplacedRgbColor(), "1.21.1 still has calculateRGBColor");
        RetromodVersion.TARGET_MC_VERSION = "1.21.2";
        assertTrue(hostReplacedRgbColor(), "1.21.2 replaced it with calculateARGBColor");
    }

    @Test
    void theForgeFamily1212ShimsRegisterTheBridge() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        for (VersionShim shim : new VersionShim[]{new Forge_1_21_1_to_1_21_2(), new NeoForge_1_21_1_to_1_21_2()}) {
            transformer.clearRedirectsForTesting();
            shim.registerRedirects(transformer);
            MethodInsnNode call = lastCall(transformer.transformClass(legacyCaller(), "test/MapTint.class"));
            assertEquals(HELPER, call.owner, shim.getShimName() + " must route calculateRGBColor through the helper");
        }
    }

    /** {@code static int tint(MapColor c, Brightness b) { return c.calculateRGBColor(b); }} */
    private static byte[] legacyCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/MapTint", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tint", HELPER_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MAP_COLOR, OLD_NAME, COLOR_DESC, false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A loader holding the helper and a stub MapColor whose calculateARGBColor returns its color. */
    private static ClassLoader defineStubs() {
        Map<String, byte[]> classes = Map.of(
                MAP_COLOR.replace('/', '.'), stubMapColor(),
                MAP_COLOR.replace('/', '.') + "$Brightness", emptyClass(MAP_COLOR + "$Brightness"),
                HELPER.replace('/', '.'), generateHelper());
        return new ClassLoader(LegacyMapColorBridgeTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private static byte[] stubMapColor() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MAP_COLOR, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "argb", "I", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, MAP_COLOR, "argb", "I");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor color = cw.visitMethod(Opcodes.ACC_PUBLIC, NEW_NAME, COLOR_DESC, null, null);
        color.visitCode();
        color.visitVarInsn(Opcodes.ALOAD, 0);
        color.visitFieldInsn(Opcodes.GETFIELD, MAP_COLOR, "argb", "I");
        color.visitInsn(Opcodes.IRETURN);
        color.visitMaxs(0, 0);
        color.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode lastCall(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : node.methods.get(0).instructions.toArray()) {
            if (insn instanceof MethodInsnNode method) call = method;
        }
        assertNotNull(call);
        return call;
    }
}
