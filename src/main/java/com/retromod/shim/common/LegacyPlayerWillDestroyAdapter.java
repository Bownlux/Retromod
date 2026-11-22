/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.function.BiPredicate;

/** Keeps a 1.20.1 block's void {@code playerWillDestroy} override running now that the host's returns the block state. */
public final class LegacyPlayerWillDestroyAdapter {

    static final String BLOCK = "net/minecraft/world/level/block/Block";
    static final String NAME = "playerWillDestroy";
    static final String ARGS = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)";
    static final String OLD_DESC = ARGS + "V";
    static final String NEW_DESC = ARGS + "Lnet/minecraft/world/level/block/state/BlockState;";

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    // The host's answer and the target it was read for; offline answers are not kept, since the target decides them.
    private static volatile String checkedTarget;
    private static volatile boolean checkedAnswer;

    private LegacyPlayerWillDestroyAdapter() {}

    /** The post-remap pass over one mod class; the same array when nothing changes. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains(NAME)
                || !hostReturnsState()) {
            return classBytes;
        }
        return apply(classBytes, RetromodTransformer::currentJarClassInheritsFrom);
    }

    /** Whether the host's method returns the state; offline, every target from 1.21 has that form. */
    static boolean hostReturnsState() {
        String target = RetromodVersion.TARGET_MC_VERSION;
        if (target != null && target.equals(checkedTarget)) return checkedAnswer;
        ClassNode block = ClassResourceInspector.read(BLOCK);
        if (block == null) return RetromodVersion.compareMcVersions(target, "1.21") >= 0;
        boolean returns = hasMethod(block, NEW_DESC) && !hasMethod(block, OLD_DESC);
        checkedAnswer = returns;
        checkedTarget = target;
        return returns;
    }

    static byte[] apply(byte[] classBytes, BiPredicate<String, String> inheritsFrom) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if (node.name.startsWith("net/minecraft/")) return classBytes;
        boolean changed = false;
        // Every call of the old shape on a block becomes the host's call, with its returned state dropped.
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && isOldCall(call, inheritsFrom)) {
                    call.desc = NEW_DESC;
                    method.instructions.insert(call, new InsnNode(Opcodes.POP));
                    changed = true;
                }
            }
        }
        // A block that overrides the old method gets the host's method, which runs the override and keeps the state.
        MethodNode override = oldOverride(node);
        // A mixin's methods are merged into the target, which already has the host's method, so it gets no bridge.
        if (override != null && !isMixin(node) && !hasMethod(node, NEW_DESC) && inheritsFrom.test(node.name, BLOCK)) {
            node.methods.add(bridge(node));
            changed = true;
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean isOldCall(MethodInsnNode call, BiPredicate<String, String> inheritsFrom) {
        if (!call.name.equals(NAME) || !call.desc.equals(OLD_DESC)) return false;
        if (call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKESPECIAL) return false;
        return call.owner.equals(BLOCK) || inheritsFrom.test(call.owner, BLOCK);
    }

    private static MethodNode oldOverride(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(NAME) && method.desc.equals(OLD_DESC)
                    && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_PRIVATE)) == 0) {
                return method;
            }
        }
        return null;
    }

    /** {@code BlockState playerWillDestroy(level, pos, state, player)}: runs the old override, then returns the state. */
    private static MethodNode bridge(ClassNode node) {
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC, NAME, NEW_DESC, null, null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int slot = 1; slot <= 4; slot++) bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
        // A direct call, so a subclass's own override cannot dispatch back into this bridge through its super call.
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, NAME, OLD_DESC, false));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
        return bridge;
    }

    private static boolean isMixin(ClassNode node) {
        for (java.util.List<org.objectweb.asm.tree.AnnotationNode> annotations : java.util.Arrays.asList(
                node.visibleAnnotations, node.invisibleAnnotations)) {
            if (annotations == null) continue;
            for (org.objectweb.asm.tree.AnnotationNode annotation : annotations) {
                if (MIXIN.equals(annotation.desc)) return true;
            }
        }
        return false;
    }

    private static boolean hasMethod(ClassNode node, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(NAME) && method.desc.equals(desc)) return true;
        }
        return false;
    }
}
