/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyBlockStates;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/**
 * Forge's {@code StairBlock(Supplier<BlockState>, Properties)}, which let a mod name its base
 * block before that block was registered. NeoForge 1.21 dropped it and kept only
 * {@code StairBlock(BlockState, Properties)}, so every such stair failed with
 * {@code NoSuchMethodError} in the mod's block registry.
 *
 * <p>The supplier is read where the stair is built, through {@link LegacyBlockStates}, which falls
 * back to stone's state when the base block is not registered yet. Applies to {@code new
 * StairBlock(...)} and to a subclass's {@code super(...)}, and only where the host has the
 * {@code BlockState} constructor and not the Forge one.
 */
public final class LegacyStairBlockBridge {

    static final String STAIR = "net/minecraft/world/level/block/StairBlock";
    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    static final String FORGE_DESC = "(Ljava/util/function/Supplier;L" + PROPERTIES + ";)V";
    static final String HOST_DESC = "(L" + STATE + ";L" + PROPERTIES + ";)V";

    private LegacyStairBlockBridge() {}

    public static void register(RetromodTransformer transformer) {
        register(transformer, ClassResourceInspector.read(STAIR));
    }

    static boolean register(RetromodTransformer transformer, ClassNode hostStair) {
        if (hostStair == null || hasConstructor(hostStair, FORGE_DESC) || !hasConstructor(hostStair, HOST_DESC)) {
            return false;
        }
        SyntheticEmbedder.registerClassResource(transformer, Forge1122BlockConstructorAdapter.STATES,
                LegacyBlockStates.class);
        // supplier, properties -> supplier.get(), properties
        transformer.registerSuperConstructorAdapter(STAIR, FORGE_DESC, HOST_DESC, mv -> {
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, Forge1122BlockConstructorAdapter.STATES, "resolve",
                    "(Ljava/util/function/Supplier;)Ljava/lang/Object;", false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, STATE);
            mv.visitInsn(Opcodes.SWAP);
        });
        return true;
    }

    private static boolean hasConstructor(ClassNode owner, String desc) {
        return owner.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(desc));
    }
}
