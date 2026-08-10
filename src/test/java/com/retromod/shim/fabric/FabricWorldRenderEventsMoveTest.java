/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression for the Fabric 1.21.10 world-render event package move (#179). */
class FabricWorldRenderEventsMoveTest {

    private static final String OLD =
            "net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents";
    private static final String CURRENT =
            "net/fabricmc/fabric/api/client/rendering/v1/world/WorldRenderEvents";

    @AfterEach
    void clear() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void afterEntitiesFieldAndSamMoveToLiveApi() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_8_to_1_21_9().registerRedirects(transformer);
        new Fabric_1_21_9_to_1_21_10().registerRedirects(transformer);

        byte[] out = transformer.transformClass(fixture(), "probe/WorldEvents");
        String[] fieldOwner = {null};
        String[] samType = {null};
        new ClassReader(out).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if ("AFTER_ENTITIES".equals(name)) fieldOwner[0] = owner;
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor,
                            org.objectweb.asm.Handle bootstrapMethodHandle,
                            Object... bootstrapMethodArguments) {
                        samType[0] = descriptor;
                    }
                };
            }
        }, 0);

        assertEquals(CURRENT, fieldOwner[0]);
        assertEquals("()L" + CURRENT + "$AfterEntities;", samType[0]);
    }

    private static byte[] fixture() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/WorldEvents", null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "register", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, OLD, "AFTER_ENTITIES",
                "Lnet/fabricmc/fabric/api/event/Event;");
        mv.visitInvokeDynamicInsn("afterEntities", "()L" + OLD + "$AfterEntities;",
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                org.objectweb.asm.Type.getMethodType("(Ljava/lang/Object;)V"),
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "probe/WorldEvents",
                        "accept", "(Ljava/lang/Object;)V", false),
                org.objectweb.asm.Type.getMethodType("(Lnet/fabricmc/fabric/api/client/"
                        + "rendering/v1/WorldRenderContext;)V"));
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/fabricmc/fabric/api/event/Event",
                "register", "(Ljava/lang/Object;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        MethodVisitor accept = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "accept", "(Ljava/lang/Object;)V", null, null);
        accept.visitCode();
        accept.visitInsn(Opcodes.RETURN);
        accept.visitMaxs(0, 1);
        accept.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
