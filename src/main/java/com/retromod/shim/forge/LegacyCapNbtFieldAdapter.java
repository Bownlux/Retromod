/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;

/** Sends a Forge 1.20.1 mod's {@code getDeclaredField("capNBT")} to a helper, on a Forge host whose ItemStack lost the field. */
public final class LegacyCapNbtFieldAdapter {

    public static final String FLAG = "legacy-capnbt-field";
    static final String HELPER = RebuiltApiClasses.ROOT + "forge/common/LegacyItemStackFields";
    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String FIELD_NAME = "capNBT";
    static final String GET_DECLARED_FIELD = "(Ljava/lang/String;)Ljava/lang/reflect/Field;";
    static final String HELPER_DESC = "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;";

    private LegacyCapNbtFieldAdapter() {}

    /** Turns the adapter on where the Forge host's ItemStack has no {@code capNBT}; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer transformer) {
        if (!ForgeHostMembers.lacksField(ITEM_STACK, FIELD_NAME, ForgeHostShape.FIRST_CHECKED_VERSION)) return;
        RebuiltApiClasses.registerTree(transformer, HELPER);
        transformer.enablePostProcess(FLAG);
    }

    /** Rewrites each {@code getDeclaredField} call whose name argument is the literal {@code "capNBT"}. */
    public static byte[] apply(byte[] classBytes) {
        // Sophisticated Core's ItemStackKey is the only reader in the Forge 1.20.1 and 1.21.1 corpora.
        if (classBytes == null || !new String(classBytes, StandardCharsets.ISO_8859_1).contains(FIELD_NAME)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!readsCapNbt(insn)) continue;
                // The helper takes the same Class and String the call took and returns a Field, so frames stay valid.
                method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "getDeclaredField",
                        HELPER_DESC, false));
                changed = true;
            }
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Whether the instruction is {@code Class.getDeclaredField} right after the constant {@code "capNBT"}. */
    private static boolean readsCapNbt(AbstractInsnNode insn) {
        if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !call.owner.equals("java/lang/Class") || !call.name.equals("getDeclaredField")
                || !call.desc.equals(GET_DECLARED_FIELD)) {
            return false;
        }
        AbstractInsnNode previous = call.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous instanceof LdcInsnNode ldc && FIELD_NAME.equals(ldc.cst);
    }
}
