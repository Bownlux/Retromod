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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Records which coordinates an old density function's own compute reads, so 26.3 can sample it on fewer axes. */
public final class LegacyDensityFunctionAxesAdapter {

    public static final String TYPE = Legacy263DensityFunctionBridge.TYPE;
    static final String CONTEXT = TYPE + "$FunctionContext";
    static final String COMPUTE_DESC = "(L" + CONTEXT + ";)D";
    static final String READ_AXES = "retromod$readAxes";
    // 26.3's axis bits, as DensityFunction declares them.
    static final int AXIS_X = 1;
    static final int AXIS_Y = 2;
    static final int AXIS_Z = 4;
    static final int ALL_AXES = AXIS_X | AXIS_Y | AXIS_Z;
    static final int UNKNOWN = -1;
    static final String VISITOR = TYPE + "$Visitor";
    static final String HOST_FUNCTION = "net/minecraft/world/level/levelgen/densityfunction/DensityFunction";
    private static final byte[] NEEDLE = CONTEXT.getBytes(StandardCharsets.UTF_8);

    private LegacyDensityFunctionAxesAdapter() {}

    /** Adds {@code retromod$readAxes()} to each class that declares its own compute: the coordinates it reads, or all. */
    public static byte[] apply(byte[] bytes) {
        if (bytes == null || !contains(bytes, NEEDLE)) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        if ((node.access & Opcodes.ACC_INTERFACE) != 0) return bytes;
        MethodNode compute = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(READ_AXES)) return bytes;
            if (method.name.equals("compute") && method.desc.equals(COMPUTE_DESC)
                    && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == 0) {
                compute = method;
            }
        }
        if (compute == null) return bytes;
        // Written even when unknown, so a subclass never inherits its parent's narrower answer.
        int axes = readAxes(compute, visitedFields(node));
        if (axes == UNKNOWN) axes = ALL_AXES;
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, READ_AXES, "()I", null, null);
        method.instructions.add(new IntInsnNode(Opcodes.BIPUSH, axes));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        node.methods.add(method);
        // The new method has no branches, so the class keeps its own frames.
        ClassWriter out = new ClassWriter(0);
        node.accept(out);
        return out.toByteArray();
    }

    /** The axes compute reads from its context, or {@link #UNKNOWN} when the context goes anywhere else. */
    static int readAxes(MethodNode compute, Set<String> visited) {
        int axes = 0;
        for (AbstractInsnNode insn : compute.instructions) {
            if (!(insn instanceof VarInsnNode local) || local.var != 1) continue;
            // A context stored elsewhere could be read anywhere.
            if (local.getOpcode() != Opcodes.ALOAD) return UNKNOWN;
            AbstractInsnNode use = next(local);
            if (!(use instanceof MethodInsnNode call)) return UNKNOWN;
            if (call.getOpcode() == Opcodes.INVOKEINTERFACE && call.owner.equals(CONTEXT) && call.desc.equals("()I")) {
                switch (call.name) {
                    case "blockX" -> axes |= AXIS_X;
                    case "blockY" -> axes |= AXIS_Y;
                    case "blockZ" -> axes |= AXIS_Z;
                    default -> {
                        return UNKNOWN;
                    }
                }
            } else if (!isChildCompute(call) || !visited.contains(fieldRead(previous(local)))) {
                // A child counts only when the class's own mapAll visits it, which is how 26.3 learns its axes.
                return UNKNOWN;
            }
        }
        return axes;
    }

    /** The fields the class's own mapAll or mapChildren hands to the visitor; none when it inherits them. */
    static Set<String> visitedFields(ClassNode node) {
        Set<String> visited = new HashSet<>();
        String mapDesc = "(L" + VISITOR + ";)L" + HOST_FUNCTION + ";";
        for (MethodNode method : node.methods) {
            if (!method.desc.equals(mapDesc) || !(method.name.equals("mapAll") || method.name.equals("mapChildren"))) continue;
            for (AbstractInsnNode insn : method.instructions) {
                String field = fieldRead(insn);
                if (field == null) continue;
                AbstractInsnNode after = next(insn);
                // Before 26.1 a function maps a child itself: field.mapAll(visitor).
                if (after instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 1
                        && next(load) instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(Legacy263DensityFunctionBridge.HELPER) && call.name.equals("mapAll")) {
                    visited.add(field);
                }
                // From 26.2 a function hands each child to the visitor: visitor.apply(field).
                if (after instanceof MethodInsnNode apply && apply.owner.equals(VISITOR) && apply.name.equals("apply")
                        && previous(previous(insn)) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                        && load.var == 1) {
                    visited.add(field);
                }
            }
        }
        return visited;
    }

    /** The name of the field {@code insn} reads from {@code this}, by a field read or a record accessor, or null. */
    static String fieldRead(AbstractInsnNode insn) {
        if (!(previous(insn) instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) return null;
        if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) return field.name;
        if (insn instanceof MethodInsnNode accessor && accessor.getOpcode() == Opcodes.INVOKEVIRTUAL
                && accessor.desc.equals("()L" + HOST_FUNCTION + ";")) {
            return accessor.name;
        }
        return null;
    }

    // A child computed at the same point; the child's own axes are added when 26.3 asks.
    private static boolean isChildCompute(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(Legacy263DensityFunctionBridge.HELPER)
                && call.name.equals("compute") && call.desc.endsWith("L" + CONTEXT + ";)D");
    }

    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn == null ? null : insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static AbstractInsnNode next(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
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
