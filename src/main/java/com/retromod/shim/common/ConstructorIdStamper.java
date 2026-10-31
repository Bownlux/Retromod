/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Gives a block or item's properties the id of the registration building it, where Minecraft's constructor receives them. */
public final class ConstructorIdStamper {

    private static final String BLOCK_PROPERTIES = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";
    private static final String ITEM_PROPERTIES = "Lnet/minecraft/world/item/Item$Properties;";
    private static final byte[] PROPERTIES = "$Properties;".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private ConstructorIdStamper() {}

    /** Passes each such constructor's properties through {@code bridge.applyBlock} or {@code applyItem}. */
    public static byte[] apply(byte[] classBytes, String bridge) {
        // The bridge stamps properties made inside a registration. A mod that makes them earlier,
        // often once for a torch and its wall torch, passes none or another block's id. Outside a
        // registration the helpers leave the properties alone.
        if (classBytes == null || !StaticFieldIdStamper.containsBytes(classBytes, PROPERTIES)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        // Retromod's own helpers pass properties through already; a mod may live under com/retromod too.
        if (node.name.startsWith("net/minecraft/") || node.name.startsWith("com/retromod/generated/")
                || node.name.startsWith("com/retromod/embedded/") || node.name.startsWith("com/retromod/shim/")) {
            return classBytes;
        }
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                // Retromod's generated bases for removed classes pass the properties straight on.
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !call.name.equals("<init>")
                        || !(call.owner.startsWith("net/minecraft/") || call.owner.startsWith("com/retromod/generated/"))) {
                    continue;
                }
                Type[] arguments = Type.getArgumentTypes(call.desc);
                int index = lastProperties(arguments);
                if (index < 0) continue;
                String properties = arguments[index].getDescriptor();
                String helper = properties.equals(BLOCK_PROPERTIES) ? "applyBlock" : "applyItem";
                // Arguments after the properties, such as NeoForge's sword Tool, wait in spare locals.
                InsnList stamp = new InsnList();
                int[] slots = new int[arguments.length];
                int local = method.maxLocals;
                for (int i = arguments.length - 1; i > index; i--) {
                    slots[i] = local;
                    stamp.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ISTORE), local));
                    local += arguments[i].getSize();
                }
                stamp.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, helper, "(" + properties + ")" + properties, false));
                for (int i = index + 1; i < arguments.length; i++) {
                    stamp.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD), slots[i]));
                }
                method.instructions.insertBefore(call, stamp);
                method.maxLocals = local;
                changed = true;
            }
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** The position of the last block or item properties argument, or -1. */
    private static int lastProperties(Type[] arguments) {
        for (int i = arguments.length - 1; i >= 0; i--) {
            String descriptor = arguments[i].getDescriptor();
            if (descriptor.equals(BLOCK_PROPERTIES) || descriptor.equals(ITEM_PROPERTIES)) return i;
        }
        return -1;
    }
}
