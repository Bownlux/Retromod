/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiPredicate;

/**
 * Keeps an old mob's {@code canBreatheUnderwater()} override working on NeoForge 1.20.5 and newer.
 *
 * <p>1.20.5 made {@code LivingEntity.canBreatheUnderwater()} final and answers it from the
 * {@code can_breathe_under_water} entity type tag, so an aquatic mob that overrode it fails to load
 * with {@code IncompatibleClassChangeError}. There is no same-shaped hook to move the override to.
 * NeoForge decides drowning through {@code canDrownInFluidType(FluidType)}, so the override is
 * renamed to a private method and a {@code canDrownInFluidType} override is added that answers
 * false whenever the old method said the mob can breathe, and defers to the parent otherwise.
 *
 * <p>Callers of {@code canBreatheUnderwater()} itself, such as vanilla AI, see the tag answer. A
 * class that already declares {@code canDrownInFluidType} keeps its own answer and only gets the
 * rename. Runs only where the host proves both the final method and the NeoForge hook.
 */
public final class LegacyBreathingOverrideAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    static final String NAME = "canBreatheUnderwater";
    static final String RENAMED = "retromod$canBreatheUnderwater";
    static final String DROWN = "canDrownInFluidType";
    static final String DROWN_DESC = "(Lnet/neoforged/neoforge/fluids/FluidType;)Z";
    private static final String EXTENSION = "net/neoforged/neoforge/common/extensions/ILivingEntityExtension";

    private static volatile Boolean hostNeedsRepair;

    private LegacyBreathingOverrideAdapter() {}

    /** Whether the host made the method final and offers NeoForge's drowning hook. */
    public static boolean hostNeedsRepair() {
        Boolean known = hostNeedsRepair;
        if (known != null) return known;
        ClassNode living = ClassResourceInspector.read(LIVING);
        ClassNode extension = ClassResourceInspector.read(EXTENSION);
        if (living == null && extension == null) {
            boolean offline = offlineNeedsRepair(com.retromod.util.McReflect.isForceNeoForge(),
                    com.retromod.core.RetromodVersion.TARGET_MC_VERSION);
            hostNeedsRepair = offline;
            return offline;
        }
        boolean needed = living != null && extension != null
                && living.methods.stream().anyMatch(m -> m.name.equals(NAME) && m.desc.equals("()Z")
                        && (m.access & Opcodes.ACC_FINAL) != 0)
                && extension.methods.stream().anyMatch(m -> m.name.equals(DROWN) && m.desc.equals(DROWN_DESC));
        hostNeedsRepair = needed;
        return needed;
    }

    /**
     * Offline, where the host is not readable, a NeoForge target from 1.20.5 on needs the repair:
     * 1.20.5 made the method final, and NeoForge has had the drowning hook since its first release.
     */
    static boolean offlineNeedsRepair(boolean neoForgeTarget, String targetVersion) {
        return neoForgeTarget
                && com.retromod.core.RetromodVersion.compareMcVersions(targetVersion, "1.20.5") >= 0;
    }

    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains(NAME) || !hostNeedsRepair()) {
            return classBytes;
        }
        return apply(classBytes, RetromodTransformer::currentJarClassInheritsFrom);
    }

    static byte[] apply(byte[] classBytes, BiPredicate<String, String> inheritsFrom) {
        if (classBytes == null) return null;
        ClassReader reader = new ClassReader(classBytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        MethodNode override = null;
        boolean hasDrownHook = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals(NAME) && method.desc.equals("()Z")
                    && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0) {
                override = method;
            }
            if (method.name.equals(DROWN) && method.desc.equals(DROWN_DESC)) hasDrownHook = true;
        }
        if (override == null || (node.access & Opcodes.ACC_INTERFACE) != 0
                || node.name.startsWith("net/minecraft/") || !inheritsFrom.test(node.superName, LIVING)) {
            return classBytes;
        }
        override.name = RENAMED;
        override.access = (override.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PRIVATE;
        if (!hasDrownHook) node.methods.add(drownHook(node));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        LOGGER.debug("Moved {}.canBreatheUnderwater onto canDrownInFluidType", node.name);
        return writer.toByteArray();
    }

    /** {@code canDrownInFluidType(type)}: false when the old override can breathe, else the parent's. */
    private static MethodNode drownHook(ClassNode node) {
        MethodNode hook = new MethodNode(Opcodes.ACC_PUBLIC, DROWN, DROWN_DESC, null, null);
        InsnList code = hook.instructions;
        LabelNode parent = new LabelNode(new Label());
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, RENAMED, "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, parent));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(parent);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, DROWN, DROWN_DESC, false));
        code.add(new InsnNode(Opcodes.IRETURN));
        return hook;
    }
}
