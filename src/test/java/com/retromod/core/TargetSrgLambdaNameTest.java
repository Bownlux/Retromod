/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import com.retromod.mapping.TargetSrgMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A lambda names the interface method it implements, and an SRG host binds that name as-is.
 * Isle of Berk's entity factories became "create" lambdas, so summoning a dragon on Forge 1.20.1
 * threw {@code AbstractMethodError} for {@code m_20721_} (#311).
 */
class TargetSrgLambdaNameTest {

    private static final String FACTORY = "net/minecraft/world/entity/EntityType$EntityFactory";
    private static final String SAM_DESC = "(Lnet/minecraft/world/entity/EntityType;"
            + "Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/Entity;";
    private static final String MOD = "com/example/ModEntities";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void entityFactoryLambdaKeepsTheHostSrgName() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerSrgNameMappings(Map.of("m_20721_", "create"), Map.of());
        transformer.registerTargetSrgNameMappings(
                Map.of(TargetSrgMapper.memberKey(FACTORY, "create", SAM_DESC), "m_20721_"),
                Map.of());

        String samName = lambdaName(transformer.transformClass(factoryLambda(), MOD));
        assertEquals("m_20721_", samName,
                "the lambda must implement the name Forge 1.20.1 calls, not the Mojang name");
    }

    private static byte[] factoryLambda() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "factory", "()L" + FACTORY + ";", null, null);
        method.visitCode();
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        Handle body = new Handle(Opcodes.H_INVOKESTATIC, MOD, "make", SAM_DESC, false);
        method.visitInvokeDynamicInsn("m_20721_", "()L" + FACTORY + ";", metafactory,
                Type.getType(SAM_DESC), body, Type.getType(SAM_DESC));
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String lambdaName(byte[] classBytes) {
        String[] name = {null};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDesc,
                            Handle bootstrap, Object... args) {
                        name[0] = indyName;
                    }
                };
            }
        }, 0);
        return name[0];
    }
}
