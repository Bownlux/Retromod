/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Gives an old particle provider the {@code createParticle} that 26.1 calls. 26.1 added a
 * {@code RandomSource} to {@code ParticleProvider.createParticle}, so a provider written for the
 * old method failed with {@code AbstractMethodError} the first time its particle spawned. The new
 * method calls the old one; the provider keeps using its own randomness, as it did.
 */
public final class LegacyParticleProviderAdapter {

    private static final String PROVIDER = "net/minecraft/client/particle/ParticleProvider";
    private static final String OPTIONS = "Lnet/minecraft/core/particles/ParticleOptions;";
    private static final String TAIL = "Lnet/minecraft/client/multiplayer/ClientLevel;DDDDDD";
    private static final String PARTICLE = "Lnet/minecraft/client/particle/Particle;";
    static final String NEW_DESC = "(" + OPTIONS + TAIL + "Lnet/minecraft/util/RandomSource;)" + PARTICLE;

    private LegacyParticleProviderAdapter() {}

    /** Returns the original array when the class is not an old provider. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION)) return classBytes;
        ClassReader reader = new ClassReader(classBytes);
        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0
                || java.util.Arrays.stream(reader.getInterfaces()).noneMatch(PROVIDER::equals)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        if (node.methods.stream().anyMatch(m -> m.name.equals("createParticle") && m.desc.equals(NEW_DESC))) return classBytes;
        // Prefer the erased bridge javac wrote for the old interface; otherwise the typed method.
        MethodNode old = node.methods.stream().filter(m -> m.name.equals("createParticle")
                && m.desc.endsWith(TAIL + ")" + PARTICLE) && (m.access & Opcodes.ACC_STATIC) == 0)
                .min(java.util.Comparator.comparing(m -> m.desc.startsWith("(" + OPTIONS) ? 0 : 1)).orElse(null);
        if (old == null) return classBytes;

        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC, "createParticle", NEW_DESC, null, null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        Type first = Type.getArgumentTypes(old.desc)[0];
        if (!first.getDescriptor().equals(OPTIONS)) bridge.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, first.getInternalName()));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        int slot = 3;
        for (int i = 0; i < 6; i++, slot += 2) bridge.instructions.add(new VarInsnNode(Opcodes.DLOAD, slot));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, old.name, old.desc, false));
        bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(bridge);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
