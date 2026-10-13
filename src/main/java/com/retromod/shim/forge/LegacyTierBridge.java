/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyTierTags;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Tool tiers written before 1.20.5 on hosts from 1.20.5 to 1.21.4, where {@code Tier} gained the
 * abstract {@code getIncorrectBlocksForDrops()} in place of a mining level. A mod's tier, often an
 * anonymous class in MCreator's tool template, did not have it, so using the tool failed with
 * {@code AbstractMethodError}. The tier gains the method, answering the tag for its old level.
 * 1.21.5 and newer rebuild tiers through {@code LegacyEquipmentBridge} instead.
 */
public final class LegacyTierBridge implements Opcodes {

    public static final String FLAG = "legacy-tier-tags";
    static final String TIER = "net/minecraft/world/item/Tier";
    static final String HELPER = "com/retromod/shim/forge/embedded/LegacyTierTags";
    private static final String METHOD = "getIncorrectBlocksForDrops";
    private static final String TAG_KEY = "net/minecraft/tags/TagKey";

    private LegacyTierBridge() {}

    public static void register(RetromodTransformer transformer) {
        register(transformer, ClassResourceInspector.read(TIER));
    }

    static void register(RetromodTransformer transformer, ClassNode hostTier) {
        if (hostTier == null || (hostTier.access & ACC_INTERFACE) == 0) return;
        boolean required = hostTier.methods.stream().anyMatch(m -> m.name.equals(METHOD)
                && m.desc.equals("()L" + TAG_KEY + ";") && (m.access & ACC_ABSTRACT) != 0);
        if (!required) return;
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyTierTags.class);
        transformer.enablePostProcess(FLAG);
    }

    /** Adds {@code getIncorrectBlocksForDrops()} to a mod's tier that lacks it. */
    public static byte[] apply(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_FRAMES);
        if (node.interfaces == null || !node.interfaces.contains(TIER) || (node.access & ACC_INTERFACE) != 0
                || node.methods.stream().anyMatch(m -> m.name.equals(METHOD))) {
            return classBytes;
        }
        ClassNode full = new ClassNode();
        new ClassReader(classBytes).accept(full, 0);
        MethodNode method = new MethodNode(ACC_PUBLIC, METHOD, "()L" + TAG_KEY + ";", null, null);
        if (full.methods.stream().anyMatch(m -> m.name.equals("getLevel") && m.desc.equals("()I"))) {
            method.instructions.add(new VarInsnNode(ALOAD, 0));
            method.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, full.name, "getLevel", "()I", false));
        } else {
            method.instructions.add(new InsnNode(ICONST_0));
        }
        method.instructions.add(new MethodInsnNode(INVOKESTATIC, HELPER, "incorrectFor", "(I)Ljava/lang/Object;", false));
        method.instructions.add(new TypeInsnNode(CHECKCAST, TAG_KEY));
        method.instructions.add(new InsnNode(ARETURN));
        full.methods.add(method);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        full.accept(writer);
        return writer.toByteArray();
    }
}
