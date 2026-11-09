/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A removed static field read and written through an Object getter and setter keeps its type: arrays are cast, primitives boxed and unboxed. */
class StaticFieldBridgeShapesTest {

    private static final String OWNER = "net/minecraft/world/Removed";
    private static final String BRIDGE = "com/retromod/generated/RemovedFields";
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an int field is unboxed on read and boxed on write")
    void primitiveFieldIsBoxed() {
        transformer.clearRedirectsForTesting();
        transformer.registerStaticFieldBridge(OWNER, "LIMIT", BRIDGE, "limit", "()Ljava/lang/Object;", "setLimit", "(Ljava/lang/Object;)V");

        List<String> code = code(transform("I"));

        assertEquals(List.of(BRIDGE + ".limit", "CHECKCAST java/lang/Integer", "java/lang/Integer.intValue",
                "java/lang/Integer.valueOf", BRIDGE + ".setLimit"), code);
    }

    @Test
    @DisplayName("an array field is cast back to its array type")
    void arrayFieldIsCast() {
        transformer.clearRedirectsForTesting();
        transformer.registerStaticFieldBridge(OWNER, "LIMIT", BRIDGE, "limit", "()Ljava/lang/Object;", "setLimit", "(Ljava/lang/Object;)V");

        List<String> code = code(transform("[Ljava/lang/String;"));

        assertEquals(List.of(BRIDGE + ".limit", "CHECKCAST [Ljava/lang/String;", BRIDGE + ".setLimit"), code);
    }

    /** {@code Removed.LIMIT = Removed.LIMIT} with the field typed {@code desc}. */
    private ClassNode transform(String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Reader", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "copy", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, OWNER, "LIMIT", desc);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, OWNER, "LIMIT", desc);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "mod/Reader")).accept(node, 0);
        return node;
    }

    private static List<String> code(ClassNode node) {
        List<String> code = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals("copy")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) code.add(call.owner + "." + call.name);
                else if (insn instanceof TypeInsnNode type && insn.getOpcode() == Opcodes.CHECKCAST) code.add("CHECKCAST " + type.desc);
            }
        }
        return code;
    }
}
