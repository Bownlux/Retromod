/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #162: the 1.21.2 {@code EntityType$Builder.build} descriptor flip
 * ({@code (String)} -> {@code (ResourceKey)}) bridged on pre-26.1 intermediary hosts
 * (ENGRAM 0.8.0-beta's {@code HorrorMod129.<clinit>} dies {@code NoSuchMethodError} without it).
 */
class Pre1_21_2EntityTypeBuildBridgeTest {

    @AfterEach
    void tearDown() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("host probe: no intermediary classes on the test classpath -> registers nothing")
    void hostProbeNoOpsOffHost() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Pre1_21_2EntityTypeBuildBridge.register(t);
        assertEquals(0, t.getMethodRedirectCount(),
                "without class_1299$class_1300 on the classpath the probe must decline");
    }

    @Test
    @DisplayName("the old build(String) call devirtualizes to the embedded bridge + CHECKCAST back")
    void buildStringCallBridged() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Pre1_21_2EntityTypeBuildBridge.registerRedirects(t);

        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/EntityReg", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "reg",
                "(Lnet/minecraft/class_1299$class_1300;)Lnet/minecraft/class_1299;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("blueice129");
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/class_1299$class_1300", "method_5905",
                "(Ljava/lang/String;)Lnet/minecraft/class_1299;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = t.transformClass(cw.toByteArray(), "test/EntityReg");
        ClassNode cn = new ClassNode();
        assertDoesNotThrow(() -> new ClassReader(out).accept(cn, 0));
        boolean bridged = false, cast = false;
        for (MethodNode m : cn.methods) {
            for (var i : m.instructions.toArray()) {
                if (i instanceof MethodInsnNode mi
                        && mi.owner.equals("com/retromod/polyfill/minecraft/RetroEntityTypeBuild")
                        && mi.name.equals("build") && mi.getOpcode() == INVOKESTATIC) {
                    bridged = true;
                    assertEquals("(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", mi.desc);
                }
                if (i instanceof TypeInsnNode ti && ti.getOpcode() == CHECKCAST
                        && ti.desc.equals("net/minecraft/class_1299")) {
                    cast = true;
                }
            }
        }
        assertTrue(bridged, "the (String) build call must devirtualize to the bridge");
        assertTrue(cast, "the Object return must cast back to the EntityType");
    }

    @Test
    @DisplayName("polyfill fail-safe: no Minecraft on the classpath -> null, never a throw")
    void polyfillFailSafe() {
        assertDoesNotThrow(() ->
                com.retromod.polyfill.minecraft.RetroEntityTypeBuild.build(new Object(), "x"));
        assertNull(com.retromod.polyfill.minecraft.RetroEntityTypeBuild.build(new Object(), "x"));
        assertNull(com.retromod.polyfill.minecraft.RetroEntityTypeBuild.build(null, "x"));
    }

}
