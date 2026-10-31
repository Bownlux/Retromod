/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** 26.3 gave Registries.CARVER and the material keys to the data registries and moved the type registries to _TYPE. */
class LegacyRegistryKeyRenames263Test {

    @Test
    @DisplayName("Each old key is renamed once: CONFIGURED_CARVER becomes CARVER, never CARVER_TYPE")
    void keysAreRenamedOnce() {
        byte[] renamed = LegacyRegistryKeyRenames263.rename(reads(
                "Registries.CARVER", "Registries.CONFIGURED_CARVER", "Registries.MATERIAL_RULE",
                "BuiltInRegistries.MATERIAL_CONDITION", "BuiltInRegistries.BLOCKSTATE_PROVIDER_TYPE",
                "Registries.BIOME"));
        assertEquals(List.of("Registries.CARVER_TYPE", "Registries.CARVER", "Registries.MATERIAL_RULE_TYPE",
                "BuiltInRegistries.MATERIAL_CONDITION_TYPE", "BuiltInRegistries.BLOCK_STATE_PROVIDER_TYPE",
                "Registries.BIOME"), fieldReads(renamed));
    }

    /** A class whose one method reads each named static field, in order. */
    private static byte[] reads(String... fields) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/Worldgen", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "keys", "()V", null, null);
        mv.visitCode();
        for (String field : fields) {
            String[] parts = field.split("\\.");
            String owner = parts[0].equals("Registries") ? LegacyRegistryKeyRenames263.REGISTRIES : LegacyRegistryKeyRenames263.BUILT_IN;
            String desc = parts[0].equals("Registries") ? "Lnet/minecraft/resources/ResourceKey;" : "Lnet/minecraft/core/Registry;";
            mv.visitFieldInsn(GETSTATIC, owner, parts[1], desc);
            mv.visitInsn(POP);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> fieldReads(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> reads = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof FieldInsnNode field) {
                reads.add(field.owner.substring(field.owner.lastIndexOf('/') + 1) + "." + field.name);
            }
        }
        return reads;
    }
}
