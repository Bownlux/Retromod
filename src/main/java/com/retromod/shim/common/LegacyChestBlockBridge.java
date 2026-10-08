/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.SWAP;

/**
 * Keeps custom chests from 1.21.8 and older constructible on newer hosts.
 *
 * <p>{@code ChestBlock}'s constructor changed shape twice. Up to 1.21.1 it was
 * {@code (Properties, Supplier<BlockEntityType>)}. 1.21.2 moved the properties last, giving
 * {@code (Supplier, Properties)}, and 1.21.9 added the open and close sounds for copper chests,
 * giving {@code (Supplier, SoundEvent, SoundEvent, Properties)}. A mod chest that calls an older
 * {@code super(...)} dies with {@code NoSuchMethodError} while it registers its blocks. Bountiful
 * Fares' ceramic chest hits this on 26.1.2, and the failure stops every later block from
 * registering.
 *
 * <p>The values are all there, only in a different order, so the {@code super(...)} call is
 * rewritten in place with stack swaps and the vanilla chest sounds are read from
 * {@code SoundEvents}. A rebase onto a generated base would also work, but it would change the
 * superclass of every chest subclass, including mixin classes that must keep extending
 * {@code ChestBlock}. The current shape passes through.
 */
public final class LegacyChestBlockBridge {

    private LegacyChestBlockBridge() {}

    static final String CHEST = "net/minecraft/world/level/block/ChestBlock";
    static final String PROPS = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    static final String SUPPLIER = "java/util/function/Supplier";
    static final String SOUNDS = "net/minecraft/sounds/SoundEvents";
    static final String SOUND = "Lnet/minecraft/sounds/SoundEvent;";

    /** 1.21.1 and older. */
    static final String PROPS_FIRST_DESC = "(L" + PROPS + ";L" + SUPPLIER + ";)V";
    /** 1.21.2 to 1.21.8. */
    static final String SUPPLIER_FIRST_DESC = "(L" + SUPPLIER + ";L" + PROPS + ";)V";
    /** 1.21.9 and newer. */
    static final String WITH_SOUNDS_DESC =
            "(L" + SUPPLIER + ";" + SOUND + SOUND + "L" + PROPS + ";)V";

    /** Registers the constructor adapters for the running host. */
    public static void register(RetromodTransformer transformer) {
        register(transformer, RetromodVersion.TARGET_MC_VERSION);
    }

    /** Registers the adapters for {@code hostVersion}, which decides the current shape. */
    static void register(RetromodTransformer transformer, String hostVersion) {
        if (!hasChestSounds(hostVersion)) {
            // (Properties, Supplier) -> (Supplier, Properties)
            transformer.registerSuperConstructorAdapter(CHEST, PROPS_FIRST_DESC,
                    SUPPLIER_FIRST_DESC, mv -> mv.visitInsn(SWAP));
            return;
        }
        // (Properties, Supplier) -> (Supplier, Properties) -> (Supplier, open, close, Properties)
        transformer.registerSuperConstructorAdapter(CHEST, PROPS_FIRST_DESC, WITH_SOUNDS_DESC,
                mv -> {
                    mv.visitInsn(SWAP);
                    insertChestSoundsBeforeTop(mv);
                });
        transformer.registerSuperConstructorAdapter(CHEST, SUPPLIER_FIRST_DESC, WITH_SOUNDS_DESC,
                LegacyChestBlockBridge::insertChestSoundsBeforeTop);
    }

    /** Whether {@code hostVersion}'s chest constructor takes the open and close sounds. */
    static boolean hasChestSounds(String hostVersion) {
        return !RetromodVersion.mcVersionExceeds("1.21.9", hostVersion);
    }

    /**
     * With the properties on top of the stack, slides the vanilla open and close sounds in under
     * them: {@code [.., props]} becomes {@code [.., open, close, props]}.
     */
    private static void insertChestSoundsBeforeTop(MethodVisitor mv) {
        mv.visitFieldInsn(GETSTATIC, SOUNDS, "CHEST_OPEN", SOUND);
        mv.visitInsn(SWAP);
        mv.visitFieldInsn(GETSTATIC, SOUNDS, "CHEST_CLOSE", SOUND);
        mv.visitInsn(SWAP);
    }
}
