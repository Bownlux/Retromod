/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;

class EntityTypeBuild26xBridgeTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    void officialBuildStringCallUsesResourceKeyBridge() {
        transformer.clearRedirectsForTesting();
        Common_1_21_11_to_26_1_ClassMoves.register(transformer);

        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, ACC_PUBLIC, "test/EntityReg26x", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "register",
                "(Lnet/minecraft/world/entity/EntityType$Builder;)"
                        + "Lnet/minecraft/world/entity/EntityType;", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitLdcInsn("example:shockwave");
        method.visitMethodInsn(INVOKEVIRTUAL,
                "net/minecraft/world/entity/EntityType$Builder", "build",
                "(Ljava/lang/String;)Lnet/minecraft/world/entity/EntityType;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        writer.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(
                writer.toByteArray(), "test/EntityReg26x")).accept(node, 0);

        boolean bridge = false;
        boolean cast = false;
        for (var methodNode : node.methods) {
            for (var instruction : methodNode.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(
                                "com/retromod/polyfill/minecraft/RetroEntityTypeBuild")
                        && call.name.equals("buildOfficial")) {
                    bridge = true;
                    assertEquals(INVOKESTATIC, call.getOpcode());
                    assertEquals("(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                            call.desc);
                }
                if (instruction instanceof TypeInsnNode type
                        && type.getOpcode() == CHECKCAST
                        && type.desc.equals("net/minecraft/world/entity/EntityType")) {
                    cast = true;
                }
            }
        }
        assertTrue(bridge, "the removed String overload must call the official bridge");
        assertTrue(cast, "the bridge result must be cast back to EntityType");
    }
}
