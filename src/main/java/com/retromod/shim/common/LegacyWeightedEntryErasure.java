/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Types an old mod's weighted entries as plain objects in its own signatures, casts and lambdas, since the old wrapper
 * is the host's {@code Weighted} now, which does not implement the rebuilt {@code WeightedEntry}. A generic
 * {@code T extends WeightedEntry} holding a wrapped value otherwise fails its erased cast, as TerraBlender's weighted
 * region layer did. Classes that implement the interface keep it; calls through it already reach a helper.
 */
public final class LegacyWeightedEntryErasure {

    public static final String FLAG = "legacy-weighted-entry-erasure";

    private static final List<String> ENTRIES = List.of(LegacyWeightedEntryBridge.ENTRY,
            LegacyWeightedEntryBridge.INTERMEDIARY_REBUILT + "WeightedEntry");
    private static final byte[] MARKER = "util/random/WeightedEntry".getBytes(StandardCharsets.ISO_8859_1);
    private static final String OBJECT = "java/lang/Object";

    private LegacyWeightedEntryErasure() {}

    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !StaticFieldIdStamper.containsBytes(classBytes, MARKER)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        // The rebuilt classes themselves keep the interface type.
        if (node.name.startsWith(RebuiltApiClasses.ROOT)) return classBytes;
        boolean changed = false;
        for (FieldNode field : node.fields) {
            String desc = erase(field.desc);
            changed |= !desc.equals(field.desc);
            field.desc = desc;
        }
        for (MethodNode method : node.methods) changed |= eraseMethod(method);
        if (!changed) return classBytes;
        // Only types moved to Object, which every frame already allows, so the frames are rewritten, not recomputed.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean eraseMethod(MethodNode method) {
        String before = method.desc;
        method.desc = erase(method.desc);
        boolean changed = !before.equals(method.desc);
        if (method.localVariables != null) {
            for (LocalVariableNode local : method.localVariables) {
                String desc = erase(local.desc);
                changed |= !desc.equals(local.desc);
                local.desc = desc;
            }
        }
        if (method.instructions == null) return changed;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                // The owner stays: a call on the interface itself is a helper call or a mod method.
                String desc = erase(call.desc);
                changed |= !desc.equals(call.desc);
                call.desc = desc;
            } else if (insn instanceof FieldInsnNode field) {
                String desc = erase(field.desc);
                changed |= !desc.equals(field.desc);
                field.desc = desc;
            } else if (insn instanceof TypeInsnNode type && insn.getOpcode() != Opcodes.NEW && insn.getOpcode() != Opcodes.INSTANCEOF) {
                // A cast or an array of entries holds wrapped values too, so it takes any object.
                String erased = eraseInternal(type.desc);
                changed |= !erased.equals(type.desc);
                type.desc = erased;
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                changed |= eraseIndy(indy);
            } else if (insn instanceof FrameNode frame) {
                changed |= eraseFrame(frame.local) | eraseFrame(frame.stack);
            }
        }
        return changed;
    }

    private static boolean eraseIndy(InvokeDynamicInsnNode indy) {
        boolean changed = false;
        String desc = erase(indy.desc);
        changed |= !desc.equals(indy.desc);
        indy.desc = desc;
        for (int i = 0; i < indy.bsmArgs.length; i++) {
            Object argument = indy.bsmArgs[i];
            Object erased = argument;
            if (argument instanceof Type type && type.getSort() == Type.METHOD) {
                erased = Type.getMethodType(erase(type.getDescriptor()));
            } else if (argument instanceof Handle handle) {
                erased = new Handle(handle.getTag(), handle.getOwner(), handle.getName(), erase(handle.getDesc()),
                        handle.isInterface());
            }
            changed |= !erased.equals(argument);
            indy.bsmArgs[i] = erased;
        }
        return changed;
    }

    private static boolean eraseFrame(List<Object> types) {
        if (types == null) return false;
        boolean changed = false;
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i) instanceof String type && ENTRIES.contains(type)) {
                types.set(i, OBJECT);
                changed = true;
            }
        }
        return changed;
    }

    /** A descriptor with every rebuilt entry type, arrays included, as Object. */
    static String erase(String desc) {
        if (desc == null) return null;
        String erased = desc;
        for (String entry : ENTRIES) erased = erased.replace("L" + entry + ";", "L" + OBJECT + ";");
        return erased;
    }

    private static String eraseInternal(String name) {
        if (ENTRIES.contains(name)) return OBJECT;
        return name.startsWith("[") ? erase(name) : name;
    }
}
