/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.Fabric_1_20_6_to_1_21;
import com.retromod.shim.fabric.Fabric_1_21_1_to_1_21_2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.Map;

import static com.retromod.shim.common.LegacyDyeColorBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 mod reading a dye's diffuse color as three floats, against the 1.21 ARGB int. */
class LegacyDyeColorBridgeTest {

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void theFloatColorCallGoesThroughTheHelper() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer);

        ClassNode node = node(transformer.transformClass(legacyCaller(), "test/DyedLayer.class"));

        MethodInsnNode call = null;
        for (AbstractInsnNode insn : node.methods.get(0).instructions.toArray()) {
            if (insn instanceof MethodInsnNode method) call = method;
        }
        assertNotNull(call);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(), "the helper is static");
        assertEquals(HELPER + "." + OLD_NAME + HELPER_DESC, call.owner + "." + call.name + call.desc);
    }

    @Test
    void theHelperIsStructurallyValid() throws Exception {
        ClassNode helper = node(generateHelper());
        for (MethodNode method : helper.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
        }
    }

    @Test
    void theRemappedFabricCallGoesThroughTheHelper() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer);

        // A Fabric 1.20.x mod's method_7787()[F arrives on 26.x as getTextureDiffuseColor()[F.
        MethodInsnNode call = lastCall(transformer.transformClass(
                caller(DYE_COLOR, NEW_NAME, OLD_DESC), "test/DyedLayer.class"));

        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals(HELPER + "." + OLD_NAME + HELPER_DESC, call.owner + "." + call.name + call.desc);
    }

    @Test
    void theIntColorCallOfANewerModIsLeftAlone() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer);

        MethodInsnNode call = lastCall(transformer.transformClass(
                caller(DYE_COLOR, NEW_NAME, "()I"), "test/DyedLayer.class"));

        assertEquals(DYE_COLOR + "." + NEW_NAME + "()I", call.owner + "." + call.name + call.desc,
                "a mod built for 1.21 or newer already calls the host's int getter");
    }

    @Test
    void anIntermediaryCallerReachesTheHelperOn26x() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerIntermediaryNameMappings(Map.of(INTERMEDIARY_NAME, NEW_NAME), Map.of());
        transformer.registerClassRedirect(INTERMEDIARY_DYE_COLOR, DYE_COLOR);
        LegacyDyeColorBridge.register(transformer);

        MethodInsnNode call = lastCall(transformer.transformClass(
                caller(INTERMEDIARY_DYE_COLOR, INTERMEDIARY_NAME, OLD_DESC), "test/DyedLayer.class"));

        assertEquals(HELPER + "." + OLD_NAME + HELPER_DESC, call.owner + "." + call.name + call.desc,
                "26.x has only the int getter, so the remapped float call must use the helper");
    }

    @Test
    void fabricRegistersTheMojangBridgeOnlyOn26x() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER));

        // An intermediary host never sees the Mojang name.
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER));
    }

    @Test
    void fabricRegistersTheIntermediaryBridgeOnlyBefore26x() throws Exception {
        RetromodTransformer.MethodKey key =
                new RetromodTransformer.MethodKey(INTERMEDIARY_DYE_COLOR, INTERMEDIARY_NAME, OLD_DESC);
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_20_6_to_1_21().registerRedirects(transformer);
        assertTrue(transformer.getSyntheticClasses().containsKey(INTERMEDIARY_HELPER));
        assertEquals(INTERMEDIARY_HELPER, transformer.getMethodRedirects().get(key).owner());

        // The helper must verify and read the dye through the intermediary int getter.
        ClassNode helper = node(transformer.getSyntheticClasses().get(INTERMEDIARY_HELPER));
        for (MethodNode method : helper.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
        }
        MethodInsnNode read = lastCall(helper);
        assertEquals(INTERMEDIARY_DYE_COLOR + "." + INTERMEDIARY_NAME + "()I",
                read.owner + "." + read.name + read.desc);

        // On 26.x class_1767 does not exist, so the intermediary helper must stay out.
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        new Fabric_1_20_6_to_1_21().registerRedirects(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(INTERMEDIARY_HELPER));
        assertNull(transformer.getMethodRedirects().get(key));
    }

    private static MethodInsnNode lastCall(byte[] bytes) {
        return lastCall(node(bytes));
    }

    private static MethodInsnNode lastCall(ClassNode node) {
        MethodInsnNode call = null;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode found) call = found;
            }
        }
        assertNotNull(call, "no call in " + node.name);
        return call;
    }

    private static byte[] legacyCaller() {
        return caller(DYE_COLOR, OLD_NAME, OLD_DESC);
    }

    /** A static method that reads a dye's color through the given owner, name and descriptor. */
    private static byte[] caller(String owner, String name, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/DyedLayer", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tint",
                "(L" + owner + ";)" + desc.substring(2), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, desc, false);
        mv.visitInsn(desc.endsWith("I") ? Opcodes.IRETURN : Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
