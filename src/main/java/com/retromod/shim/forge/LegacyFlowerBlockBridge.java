/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyFlowerEffects;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/**
 * Forge's {@code FlowerBlock(Supplier<MobEffect>, int, Properties)}, which MCreator's plant template
 * extends. The host takes {@code (Holder<MobEffect>, float, Properties)} since 1.20.5, and the int
 * was already the stew effect's seconds, so the effect is wrapped as a holder and the seconds are
 * kept. Applies only where the host has the new constructor and not the Forge one.
 */
public final class LegacyFlowerBlockBridge {

    static final String FLOWER = "net/minecraft/world/level/block/FlowerBlock";
    static final String HELPER = "com/retromod/shim/forge/embedded/LegacyFlowerEffects";
    private static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    static final String FORGE_DESC = "(Ljava/util/function/Supplier;IL" + PROPERTIES + ";)V";
    static final String HOST_DESC = "(Lnet/minecraft/core/Holder;FL" + PROPERTIES + ";)V";

    private LegacyFlowerBlockBridge() {}

    public static void register(RetromodTransformer transformer) {
        register(transformer, ClassResourceInspector.read(FLOWER));
    }

    static void register(RetromodTransformer transformer, ClassNode hostFlower) {
        if (hostFlower == null || hasConstructor(hostFlower, FORGE_DESC) || !hasConstructor(hostFlower, HOST_DESC)) return;
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyFlowerEffects.class);
        // supplier, seconds, properties -> holder, seconds as a float, properties
        transformer.registerSuperConstructorAdapter(FLOWER, FORGE_DESC, HOST_DESC, mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "stash",
                    "(Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, PROPERTIES);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "holder", "()Ljava/lang/Object;", false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/core/Holder");
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "seconds", "()F", false);
            mv.visitInsn(Opcodes.SWAP);
        });
    }

    private static boolean hasConstructor(ClassNode owner, String desc) {
        return owner.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(desc));
    }
}
