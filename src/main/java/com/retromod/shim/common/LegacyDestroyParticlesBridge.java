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
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiPredicate;

/** Moves {@code Block.spawnDestroyParticles} with a player onto 26.3's {@code spawnDestroyByEntityParticles}, calls and overrides alike. */
public final class LegacyDestroyParticlesBridge {

    static final String BLOCK = "net/minecraft/world/level/block/Block";
    static final String PLAYER = "net/minecraft/world/entity/player/Player";
    static final String OLD_NAME = "spawnDestroyParticles";
    static final String NEW_NAME = "spawnDestroyByEntityParticles";
    static final String OLD_DESC = "(Lnet/minecraft/world/level/Level;L" + PLAYER + ";Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;)V";
    static final String NEW_DESC = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;)V";
    /** The name an old override keeps, so the new method can call it without the redirect turning the call back. */
    static final String RENAMED = "retromod$spawnDestroyParticles";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private static volatile Boolean hostMoved;

    private LegacyDestroyParticlesBridge() {}

    /** Sends calls to the old method to the new one, on a host that made the move. */
    public static void register(RetromodTransformer transformer) {
        if (!hostMoved()) return;
        // The player is an entity, so the arguments carry over as they are.
        transformer.registerInheritedMethodRedirect(BLOCK, OLD_NAME, OLD_DESC, BLOCK, NEW_NAME, NEW_DESC);
    }

    /** Whether the host's blocks spawn destroy particles for any entity and no longer for a player alone. */
    static boolean hostMoved() {
        Boolean known = hostMoved;
        if (known != null) return known;
        ClassNode block = ClassResourceInspector.read(BLOCK);
        // Offline the host classes are not readable, so the host version decides each time.
        if (block == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        boolean moved = declares(block, NEW_NAME, NEW_DESC) && !declares(block, OLD_NAME, OLD_DESC);
        hostMoved = moved;
        return moved;
    }

    /** Keeps an old override called and points calls through an accessor's invoker at the retyped invoker. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !new String(classBytes, StandardCharsets.ISO_8859_1).contains("pawnDestroyParticles")
                || !hostMoved()) {
            return classBytes;
        }
        // The invoker repair needs the indexed host, so its callers are retyped only when it can run.
        var host = RetromodTransformer.getInstance().getFuzzyResolver();
        return apply(classBytes, RetromodTransformer::currentJarClassInheritsFrom, host != null && host.isIndexed());
    }

    static byte[] apply(byte[] classBytes, BiPredicate<String, String> inheritsFrom, boolean invokersRetyped) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = invokersRetyped && retypeInvokerCalls(node);
        changed |= keepOldOverride(node, inheritsFrom);
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Calls through an accessor invoker the mixin repair retyped take an entity now, which a player is. */
    private static boolean retypeInvokerCalls(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && call.desc.equals(OLD_DESC) && call.name.toLowerCase().endsWith("spawndestroyparticles")
                        && !call.owner.startsWith("net/minecraft/")) {
                    call.desc = NEW_DESC;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** A block that overrode the old method gets the new one, which runs the old override as before. */
    private static boolean keepOldOverride(ClassNode node, BiPredicate<String, String> inheritsFrom) {
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/") || isMixin(node)
                || declares(node, NEW_NAME, NEW_DESC) || node.version < Opcodes.V1_7) {
            return false;
        }
        MethodNode override = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(OLD_NAME) && method.desc.equals(OLD_DESC)
                    && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0) {
                override = method;
            }
        }
        if (override == null || !inheritsFrom.test(node.superName, BLOCK)) return false;
        override.name = RENAMED;
        node.methods.add(newMethod(node));
        return true;
    }

    /** {@code spawnDestroyByEntityParticles(level, entity, pos, state)}: the old override when a player breaks the block, else the parent's. */
    private static MethodNode newMethod(ClassNode node) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, NEW_NAME, NEW_DESC, null, null);
        InsnList code = method.instructions;
        LabelNode notPlayer = new LabelNode(new Label());
        // The old method only ran from playerWillDestroy. Explosions, pistons and commands now pass
        // another entity or none, and an old override that reads the player would fail on those.
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new TypeInsnNode(Opcodes.INSTANCEOF, PLAYER));
        code.add(new JumpInsnNode(Opcodes.IFEQ, notPlayer));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, PLAYER));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, RENAMED, OLD_DESC, false));
        code.add(new InsnNode(Opcodes.RETURN));
        // Anything else gets the default effect, as those breaks did before.
        code.add(notPlayer);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        for (int slot = 0; slot <= 4; slot++) code.add(new VarInsnNode(Opcodes.ALOAD, slot));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, NEW_NAME, NEW_DESC, false));
        code.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static boolean isMixin(ClassNode node) {
        for (List<AnnotationNode> annotations : List.of(
                node.visibleAnnotations == null ? List.<AnnotationNode>of() : node.visibleAnnotations,
                node.invisibleAnnotations == null ? List.<AnnotationNode>of() : node.invisibleAnnotations)) {
            for (AnnotationNode annotation : annotations) {
                if (MIXIN.equals(annotation.desc)) return true;
            }
        }
        return false;
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }
}
