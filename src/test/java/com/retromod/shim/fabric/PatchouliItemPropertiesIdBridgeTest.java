/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Regression for Patchouli's guide-book item on Fabric 1.21.4 and newer. */
class PatchouliItemPropertiesIdBridgeTest {

    @AfterEach
    void clear() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void guideBookPropertiesReceiveTheirRegistryKeyBeforeItemConstruction() {
        String savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        try {
            byte[] repaired = LegacyFabricItemIdRepair.apply(oldItemModBook(),
                    "vazkii/patchouli/common/item/ItemModBook");
            ClassNode output = new ClassNode();
            new ClassReader(repaired).accept(output, 0);
            var instructions = Arrays.asList(output.methods.get(0).instructions.toArray());

            assertTrue(instructions.stream().anyMatch(i -> i instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && "vazkii/patchouli/common/item/PatchouliItems".equals(field.owner)
                    && "BOOK_ID".equals(field.name)));
            assertTrue(instructions.stream().anyMatch(i -> i instanceof MethodInsnNode call
                    && "net/minecraft/class_5321".equals(call.owner)
                    && "method_29179".equals(call.name)));
            assertTrue(instructions.stream().anyMatch(i -> i instanceof MethodInsnNode call
                    && "net/minecraft/class_1792$class_1793".equals(call.owner)
                    && "method_63686".equals(call.name)));
            assertTrue(output.methods.get(0).maxStack >= 4,
                    "the injected registry key operands must fit in the constructor stack");
        } finally {
            RetromodVersion.TARGET_MC_VERSION = savedVersion;
        }
    }

    @Test
    void removedIdentifierGsonAdapterIsDroppedWithoutDroppingOtherAdapters() {
        String savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        try {
            byte[] repaired = LegacyPatchouliGsonRepair.apply(oldSerializationUtil(),
                    "vazkii/patchouli/common/util/SerializationUtil");
            ClassNode output = new ClassNode();
            new ClassReader(repaired).accept(output, 0);
            var instructions = Arrays.asList(output.methods.get(0).instructions.toArray());

            assertFalse(instructions.stream().anyMatch(i -> i instanceof TypeInsnNode type
                    && "net/minecraft/class_2960$class_2961".equals(type.desc)));
            assertTrue(instructions.stream().anyMatch(i -> i instanceof TypeInsnNode type
                    && "vazkii/patchouli/api/IVariable$Serializer".equals(type.desc)));
        } finally {
            RetromodVersion.TARGET_MC_VERSION = savedVersion;
        }
    }

    private static byte[] oldItemModBook() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                "vazkii/patchouli/common/item/ItemModBook", null,
                "net/minecraft/class_1792", null);
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitTypeInsn(Opcodes.NEW, "net/minecraft/class_1792$class_1793");
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "net/minecraft/class_1792$class_1793", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.ICONST_1);
        constructor.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/class_1792$class_1793", "method_7889",
                "(I)Lnet/minecraft/class_1792$class_1793;", false);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "net/minecraft/class_1792", "<init>",
                "(Lnet/minecraft/class_1792$class_1793;)V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] oldSerializationUtil() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                "vazkii/patchouli/common/util/SerializationUtil", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "com/google/gson/GsonBuilder");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "com/google/gson/GsonBuilder", "<init>", "()V", false);
        method.visitLdcInsn(org.objectweb.asm.Type.getObjectType("net/minecraft/class_2960"));
        method.visitTypeInsn(Opcodes.NEW, "net/minecraft/class_2960$class_2961");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "net/minecraft/class_2960$class_2961", "<init>", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/google/gson/GsonBuilder", "registerTypeAdapter",
                "(Ljava/lang/reflect/Type;Ljava/lang/Object;)Lcom/google/gson/GsonBuilder;", false);
        method.visitLdcInsn(org.objectweb.asm.Type.getObjectType("vazkii/patchouli/api/IVariable"));
        method.visitTypeInsn(Opcodes.NEW, "vazkii/patchouli/api/IVariable$Serializer");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "vazkii/patchouli/api/IVariable$Serializer", "<init>", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/google/gson/GsonBuilder", "registerTypeAdapter",
                "(Ljava/lang/reflect/Type;Ljava/lang/Object;)Lcom/google/gson/GsonBuilder;", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
