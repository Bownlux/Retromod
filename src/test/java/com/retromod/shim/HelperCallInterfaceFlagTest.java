/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.api.fabric.ClothConfigApiShim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A static interface call sent to a Retromod helper class names the helper as a class. */
class HelperCallInterfaceFlagTest {

    private static final String CONFIG_BUILDER = "me/shedaniel/clothconfig2/api/ConfigBuilder";
    private static final String CLOTH_SHIM = "com/retromod/shim/api/fabric/embedded/ClothConfigShim";

    private final String savedVersion = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedVersion;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void clothConfigBuilderCreateCallsTheHelperAsAClass() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new ClothConfigApiShim().registerRedirects(transformer);

        MethodInsnNode call = helperCall(transformer.transformClass(configScreen(), "mod/ConfigScreen"));

        assertNotNull(call, "ConfigBuilder.create() is redirected to the helper");
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        // An InterfaceMethodref to a class fails with IncompatibleClassChangeError when called.
        assertFalse(call.itf, "ConfigBuilder is an interface, but the helper is a class");
    }

    /** A mod's config screen factory, which calls the static interface method ConfigBuilder.create(). */
    private static byte[] configScreen() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/ConfigScreen", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "builder",
                "()L" + CONFIG_BUILDER + ";", null, null);
        m.visitCode();
        m.visitMethodInsn(Opcodes.INVOKESTATIC, CONFIG_BUILDER, "create", "()L" + CONFIG_BUILDER + ";", true);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode helperCall(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(CLOTH_SHIM)) return call;
            }
        }
        return null;
    }
}
