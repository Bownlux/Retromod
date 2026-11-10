/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.charset.StandardCharsets;

/** Lets old code that reads a biome condition's key list, as Citadel does, see 26.3's conditions and every mod's key condition. */
public final class LegacyBiomeConditionReadAdapter {

    static final String CONDITION = "net/minecraft/world/level/levelgen/material/condition/BiomeCondition";
    public static final String HELPER = Legacy263SurfaceRuleBridge.HELPER;
    private static final String OLD_FIELD = "biomes";
    private static final String OLD_FIELD_DESC = "Ljava/util/List;";
    private static final byte[] NEEDLE = CONDITION.getBytes(StandardCharsets.UTF_8);

    private LegacyBiomeConditionReadAdapter() {}

    /** Rewrites each method that reads the old {@code biomes} list, with its checks and casts of the condition. */
    public static byte[] apply(byte[] bytes) {
        // The checks and casts go through the helper too, because a condition another mod built from keys is not 26.3's class.
        if (bytes == null || !contains(bytes, NEEDLE)) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (readsOldList(method)) {
                adapt(method);
                changed = true;
            }
        }
        if (!changed) return bytes;
        // Each replacement keeps the stack shape and the condition's type, so the frames stay valid.
        ClassWriter out = new ClassWriter(0);
        node.accept(out);
        return out.toByteArray();
    }

    private static boolean readsOldList(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (isOldListRead(insn)) return true;
        }
        return false;
    }

    private static boolean isOldListRead(AbstractInsnNode insn) {
        return insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(CONDITION)
                && field.name.equals(OLD_FIELD) && field.desc.equals(OLD_FIELD_DESC);
    }

    private static void adapt(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (isOldListRead(insn)) {
                replace(method, insn, "biomes", "(L" + CONDITION + ";)Ljava/util/List;");
            } else if (insn instanceof TypeInsnNode type && type.desc.equals(CONDITION)) {
                if (type.getOpcode() == Opcodes.INSTANCEOF) {
                    replace(method, insn, "isBiomeCondition", "(Ljava/lang/Object;)Z");
                } else if (type.getOpcode() == Opcodes.CHECKCAST) {
                    replace(method, insn, "asBiomeCondition", "(Ljava/lang/Object;)L" + CONDITION + ";");
                }
            }
        }
    }

    private static void replace(MethodNode method, AbstractInsnNode insn, String name, String desc) {
        method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, name, desc, false));
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
