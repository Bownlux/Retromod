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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Map;

/** Points the calls whose meaning depended on which of the two old weighted lists was used at helpers, before both move onto WeightedList. */
public final class LegacyWeightedListPrePass {

    /** Post-process flag the weighted list bridge sets on hosts that merged the lists. */
    public static final String FLAG = "legacy-weighted-list-calls";

    private static final String SIMPLE = "net/minecraft/util/random/SimpleWeightedRandomList";
    private static final String ENTRIES = "net/minecraft/util/random/WeightedRandomList";
    // Fabric mods still carry intermediary names here, before the remap.
    private static final Map<String, String> NAMES = Map.of(
            "net/minecraft/class_6005", SIMPLE, "net/minecraft/class_6012", ENTRIES,
            SIMPLE, SIMPLE, ENTRIES, ENTRIES);
    // The 26.x table names the Fabric create ids of, the host's own factory, so they are matched here by id.
    private static final Map<String, String> METHODS = Map.of(
            "method_34992", "getRandom", "method_34994", "unwrap", "method_34991", "codec",
            "method_34988", "create", "method_34989", "create", "method_34990", "create");

    private LegacyWeightedListPrePass() {}

    /** Returns the original array when the class makes none of these calls. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null) return null;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && NAMES.containsKey(call.owner)) changed |= rewrite(call);
            }
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** One call by its original owner; the helper's descriptor names the old list type, which the class move then renames. */
    private static boolean rewrite(MethodInsnNode call) {
        String list = NAMES.get(call.owner);
        String name = METHODS.getOrDefault(call.name, call.name);
        String receiver = "(L" + call.owner + ";";
        String helper = LegacyWeightedListBridge.HELPER;
        // A simple list's inherited getRandom handed out the value with its weight; the merged list hands out the value.
        if (list.equals(SIMPLE) && name.equals("getRandom") && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && call.desc.endsWith(")Ljava/util/Optional;")) {
            return toStatic(call, helper, "wrappedRandom", receiver + call.desc.substring(1));
        }
        // A list of entries handed out the entries themselves and read them as a plain list.
        if (list.equals(ENTRIES) && name.equals("unwrap") && call.getOpcode() == Opcodes.INVOKEVIRTUAL) {
            return toStatic(call, helper, "entries", receiver + call.desc.substring(1));
        }
        if (list.equals(ENTRIES) && name.equals("codec") && call.getOpcode() == Opcodes.INVOKESTATIC) {
            return toStatic(call, helper, "entryCodec", call.desc);
        }
        // Both old lists built a list of entries from create, each entry weighted by its own weight.
        if (name.equals("create") && call.getOpcode() == Opcodes.INVOKESTATIC) {
            String result = call.desc.substring(call.desc.indexOf(')') + 1);
            if (call.desc.startsWith("(Ljava/util/List;)")) return toStatic(call, helper, "fromEntries", "(Ljava/util/List;)" + result);
            if (call.desc.startsWith("([")) return toStatic(call, helper, "fromEntries", "([Ljava/lang/Object;)" + result);
            if (call.desc.startsWith("()")) return toStatic(call, helper, "noEntries", call.desc);
        }
        return false;
    }

    private static boolean toStatic(MethodInsnNode call, String owner, String name, String desc) {
        call.setOpcode(Opcodes.INVOKESTATIC);
        call.owner = owner;
        call.name = name;
        call.desc = desc;
        call.itf = false;
        return true;
    }
}
