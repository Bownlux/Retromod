/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;

class OfflineRegistrationResetTest {
    private static final String OLD_CLASS = "net/minecraft/old/FirstInputType";
    private static final String NEW_CLASS = "net/minecraft/newapi/FirstInputType";
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void cleanUp() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    void reflectionRedirectSnapshotDoesNotCrossOfflineInputs() {
        transformer.resetOfflineRegistrations();
        transformer.registerClassRedirect(OLD_CLASS, NEW_CLASS);
        assertEquals(NEW_CLASS.replace('/', '.'), reflectedClassName(
                transformer.transformClass(reflectiveLookup(), "fixture/FirstInput")));

        transformer.resetOfflineRegistrations();
        assertEquals(OLD_CLASS.replace('/', '.'), reflectedClassName(
                transformer.transformClass(reflectiveLookup(), "fixture/SecondInput")));
    }

    private static byte[] reflectiveLookup() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                "fixture/ReflectionLookup", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "lookup", "()Ljava/lang/Class;", null,
                new String[] {"java/lang/ClassNotFoundException"});
        method.visitCode();
        method.visitLdcInsn(OLD_CLASS.replace('/', '.'));
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String reflectedClassName(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream()
                .filter(method -> "lookup".equals(method.name))
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(LdcInsnNode.class::isInstance)
                .map(LdcInsnNode.class::cast)
                .map(instruction -> instruction.cst)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
