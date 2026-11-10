/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Turns {@code instanceof T} into a static {@code (Object)Z} helper call, for a rebuilt type each mod carries its own copy of. */
final class InstanceofHelperAdapter {

    /** The static helper that answers the check, by owner and name. */
    record Helper(String owner, String name) {}

    static final String HELPER_DESC = "(Ljava/lang/Object;)Z";

    private InstanceofHelperAdapter() {}

    static byte[] apply(byte[] bytes, Map<String, Helper> helpers) {
        if (helpers.isEmpty() || !namesAny(bytes, helpers.keySet())) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            changed |= rewrite(method, helpers);
        }
        if (!changed) return bytes;
        // The helper leaves an int where instanceof did, so the frames and stack sizes still hold.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean rewrite(MethodNode method, Map<String, Helper> helpers) {
        if (method.instructions.size() == 0) return false;
        Set<String> castTo = castTypes(method);
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() != Opcodes.INSTANCEOF) continue;
            String type = ((TypeInsnNode) insn).desc;
            Helper helper = helpers.get(type);
            // A method that also casts to the type keeps the exact check, since the cast would fail on
            // another mod's copy. A pattern binding such as instanceof T t always compiles to a cast.
            if (helper == null || castTo.contains(type)) continue;
            method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, helper.owner(), helper.name(),
                    HELPER_DESC, false));
            changed = true;
        }
        return changed;
    }

    private static Set<String> castTypes(MethodNode method) {
        Set<String> types = new HashSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.CHECKCAST) types.add(((TypeInsnNode) insn).desc);
        }
        return types;
    }

    // A cheap scan of the raw bytes, so a class that never names a registered type is not parsed.
    private static boolean namesAny(byte[] bytes, Set<String> types) {
        for (String type : types) {
            if (contains(bytes, type.getBytes(StandardCharsets.UTF_8))) return true;
        }
        return false;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
