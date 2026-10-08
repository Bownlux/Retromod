/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Moves a mod's {@code positionRider(Entity)} override onto the overridable form Minecraft 1.20
 * kept.
 *
 * <p>1.20 made {@code Entity.positionRider(Entity)} final. It now calls the protected
 * {@code positionRider(Entity, Entity.MoveFunction)}, which is the one subclasses override. A
 * rideable mob from an older version still overrides the one-argument method, and the JVM refuses
 * to load the class ({@code IncompatibleClassChangeError: ... overrides final method}). Every
 * mount in Isle of Berk failed this way, which stopped its registration (#311).
 *
 * <p>The override is renamed to the two-argument method, with the move function as an unused
 * second parameter. Its own {@code super.positionRider(passenger)} call is retargeted to the
 * two-argument super method. Left alone it would re-enter the final method, which dispatches
 * straight back to this override.
 */
public final class LegacyPositionRiderAdapter {

    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String NAME = "positionRider";
    static final String ONE_ARG = "(L" + ENTITY + ";)V";
    static final String TWO_ARG = "(L" + ENTITY + ";L" + ENTITY + "$MoveFunction;)V";

    private LegacyPositionRiderAdapter() {
    }

    /**
     * @return the repaired class, or the same array when it declares no stale override
     */
    public static byte[] apply(byte[] classBytes, RetromodTransformer transformer) {
        return apply(classBytes,
                transformer.remapQualifiedMethodName(ENTITY, NAME, ONE_ARG),
                transformer.remapQualifiedMethodName(ENTITY, NAME, TWO_ARG));
    }

    /** Variant with the host's method names already resolved. */
    static byte[] apply(byte[] classBytes, String oneArgName, String twoArgName) {
        if (classBytes == null || oneArgName == null || twoArgName == null
                || oneArgName.equals(twoArgName)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")
                || node.name.startsWith("com/retromod/")) {
            return classBytes;
        }
        MethodNode stale = find(node, oneArgName, ONE_ARG);
        if (stale == null || find(node, twoArgName, TWO_ARG) != null
                || (stale.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0) {
            return classBytes;
        }

        moveToTwoArgumentForm(stale, twoArgName, oneArgName);
        try {
            ClassWriter writer = new SafeClassWriter(new ClassReader(classBytes),
                    ClassWriter.COMPUTE_FRAMES);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable failed) {
            return classBytes; // never ship a half-rewritten class
        }
    }

    private static void moveToTwoArgumentForm(MethodNode method, String twoArgName,
            String oneArgName) {
        // Slot 2 becomes the move function, so every later local moves up one slot.
        int moveFunctionSlot = 2;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof VarInsnNode var && var.var >= moveFunctionSlot) {
                var.var++;
            } else if (insn instanceof IincInsnNode iinc && iinc.var >= moveFunctionSlot) {
                iinc.var++;
            } else if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && oneArgName.equals(call.name) && ONE_ARG.equals(call.desc)) {
                method.instructions.insertBefore(call,
                        new VarInsnNode(Opcodes.ALOAD, moveFunctionSlot));
                call.name = twoArgName;
                call.desc = TWO_ARG;
            }
        }
        if (method.localVariables != null) {
            for (LocalVariableNode local : method.localVariables) {
                if (local.index >= moveFunctionSlot) {
                    local.index++;
                }
            }
        }
        method.name = twoArgName;
        method.desc = TWO_ARG;
        method.signature = null;
        method.maxLocals++;
        // The new method is protected in Minecraft; a public override stays legal.
        method.access &= ~Opcodes.ACC_FINAL;
        method.parameters = null;
        method.visibleParameterAnnotations = null;
        method.invisibleParameterAnnotations = null;
    }

    private static MethodNode find(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }
}
