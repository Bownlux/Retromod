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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression for ENGRAM's 1.20.1 attribute builder calls on Fabric 1.21.11. */
class FabricAttributeHolderMigrationTest {

    private static final String ATTRIBUTES = "net/minecraft/class_5134";
    private static final String BUILDER = "net/minecraft/class_5132$class_5133";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("#179: Attribute constants and Builder.add use Holder descriptors")
    void attributeBuilderRetypedToHolder() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);

        byte[] output = transformer.transformClass(engramAttributeFactory(), "test/EngramEntity");
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);

        boolean sawField = false;
        boolean sawAdd = false;
        for (var instruction : node.methods.get(0).instructions) {
            if (instruction instanceof FieldInsnNode field && field.owner.equals(ATTRIBUTES)) {
                assertEquals("Lnet/minecraft/class_6880;", field.desc);
                sawField = true;
            }
            if (instruction instanceof MethodInsnNode method && method.name.equals("method_26868")) {
                assertEquals("(Lnet/minecraft/class_6880;D)L" + BUILDER + ";", method.desc);
                sawAdd = true;
            }
        }
        assertTrue(sawField && sawAdd, "the fixture must contain both rewritten instructions");
    }

    private static byte[] engramAttributeFactory() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/EngramEntity", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "attributes", "(L" + BUILDER + ";)L" + BUILDER + ";", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETSTATIC, ATTRIBUTES, "field_23716",
                "Lnet/minecraft/class_1320;");
        method.visitLdcInsn(20.0D);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "method_26868",
                "(Lnet/minecraft/class_1320;D)L" + BUILDER + ";", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(4, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
