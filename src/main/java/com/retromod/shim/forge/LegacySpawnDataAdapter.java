/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Arrays;

/**
 * Bridges Forge's {@code IEntityAdditionalSpawnData} to NeoForge's {@code IEntityWithComplexSpawn}.
 *
 * <p>Both interfaces let an entity append data to its spawn packet through
 * {@code writeSpawnData} and {@code readSpawnData}. NeoForge renamed the interface and, with the
 * 1.20.5 network rework, changed the buffer parameter from {@code FriendlyByteBuf} to its subclass
 * {@code RegistryFriendlyByteBuf}. A class rename alone leaves the old entity with the old
 * descriptors, so the spawn packet hits {@code AbstractMethodError}.
 *
 * <p>For each class that names the interface directly, this adds the two
 * {@code RegistryFriendlyByteBuf} methods and forwards them to the mod's own
 * {@code FriendlyByteBuf} methods. The forward is a virtual call, so a subclass override of the old
 * method still runs, and the wider parameter accepts the new buffer unchanged.
 */
public final class LegacySpawnDataAdapter {

    static final String FORGE_INTERFACE = "net/minecraftforge/entity/IEntityAdditionalSpawnData";
    static final String NEOFORGE_INTERFACE = "net/neoforged/neoforge/entity/IEntityWithComplexSpawn";
    private static final String OLD_DESC = "(Lnet/minecraft/network/FriendlyByteBuf;)V";
    private static final String NEW_DESC = "(Lnet/minecraft/network/RegistryFriendlyByteBuf;)V";
    private static final String[] METHODS = {"writeSpawnData", "readSpawnData"};

    private LegacySpawnDataAdapter() {}

    /** Renames the interface; the per-class bridges are added by {@link #apply}. */
    static void register(RetromodTransformer transformer) {
        transformer.registerClassRedirect(FORGE_INTERFACE, NEOFORGE_INTERFACE);
    }

    /** Returns the original array unless the class implements the renamed interface directly. */
    public static byte[] apply(byte[] classBytes) {
        return apply(classBytes, RetromodVersion.TARGET_MC_VERSION);
    }

    /**
     * Adds the forwarders only on 1.20.5 and newer. NeoForge 1.20.2 to 1.20.4 declares the
     * interface with {@code FriendlyByteBuf}, so a mod built for those hosts already matches it.
     */
    static byte[] apply(byte[] classBytes, String hostVersion) {
        if (classBytes == null) return null;
        if (hostVersion == null || RetromodVersion.compareMcVersions(hostVersion, "1.20.5") < 0) {
            return classBytes;
        }
        try {
            ClassReader reader = new ClassReader(classBytes);
            if (!Arrays.asList(reader.getInterfaces()).contains(NEOFORGE_INTERFACE)) return classBytes;
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            int added = 0;
            for (String name : METHODS) {
                if (declares(node, name, OLD_DESC) && !declares(node, name, NEW_DESC)) {
                    node.methods.add(forwarder(node.name, name));
                    added++;
                }
            }
            if (added == 0) return classBytes;
            ClassWriter writer = new ClassWriter(reader, 0);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException e) {
            return classBytes;
        }
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static MethodNode forwarder(String owner, String name) {
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, name, NEW_DESC, null, null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, name, OLD_DESC, false));
        bridge.instructions.add(new InsnNode(Opcodes.RETURN));
        bridge.maxStack = 2;
        bridge.maxLocals = 2;
        return bridge;
    }
}
