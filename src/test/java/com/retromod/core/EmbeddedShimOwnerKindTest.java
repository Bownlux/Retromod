/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import com.retromod.shim.fabric.embedded.ResourceManagerHelperShim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EmbeddedShimOwnerKindTest {

    private static final String OLD = "test/api/OldHelper";
    private static final String OLD_ARG = "test/api/OldArgument";
    private static final String SHIM =
            "com/retromod/shim/fabric/embedded/ResourceManagerHelperShim";

    private RetromodTransformer transformer;

    @BeforeEach
    void resetTransformer() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @Test
    void interfaceCallsBecomeClassCallsWhenClassRedirectTargetsACompiledShim() {
        transformer.registerEmbeddedShim(ResourceManagerHelperShim.class.getName());
        transformer.registerClassRedirect(OLD, SHIM);
        transformer.registerMethodRedirect(OLD, "get",
                "(L" + OLD_ARG + ";)L" + OLD + ";",
                SHIM, "get", "(Ljava/lang/Object;)L" + SHIM + ";");
        transformer.registerMethodRedirect(OLD, "registerReloadListener",
                "(Ljava/lang/Object;)V",
                SHIM, "registerReloadListener", "(Ljava/lang/Object;)V");
        transformer.registerClassRedirect(OLD_ARG, "java/lang/Object");

        byte[] output = transformer.transformClass(caller(), "test/EmbeddedShimCaller");
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        node.methods.stream()
                .filter(method -> method.name.equals("use"))
                .findFirst()
                .orElseThrow()
                .instructions
                .forEach(instruction -> {
                    if (instruction instanceof MethodInsnNode call) {
                        calls.add(call);
                    }
                });

        assertEquals(2, calls.size());
        assertEquals(Opcodes.INVOKESTATIC, calls.get(0).getOpcode());
        assertEquals(SHIM, calls.get(0).owner);
        assertEquals("(Ljava/lang/Object;)L" + SHIM + ";", calls.get(0).desc);
        assertFalse(calls.get(0).itf,
                "a static method on a class needs Methodref, even when the old owner was an interface");
        assertEquals(Opcodes.INVOKEVIRTUAL, calls.get(1).getOpcode());
        assertEquals(SHIM, calls.get(1).owner);
        assertEquals("(Ljava/lang/Object;)V", calls.get(1).desc);
        assertFalse(calls.get(1).itf,
                "an instance method on a class cannot retain INVOKEINTERFACE");
    }

    private static byte[] caller() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/EmbeddedShimCaller",
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "use", "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, OLD, "get",
                "(L" + OLD_ARG + ";)L" + OLD + ";", true);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, OLD, "registerReloadListener",
                "(Ljava/lang/Object;)V", true);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 2);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
