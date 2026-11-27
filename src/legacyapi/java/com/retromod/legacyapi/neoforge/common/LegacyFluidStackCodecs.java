/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.common;

import com.retromod.shim.common.embedded.LegacyLog;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.fluids.FluidStack;

/** NeoForge 1.21.1's fluid stack tag reads and writes, answered through the codecs 26.x left them to. */
public final class LegacyFluidStackCodecs {

    private LegacyFluidStackCodecs() {}

    /** NeoForge 1.21.1's {@code FluidStack.parseOptional(registries, tag)}: an empty or unreadable tag gives the empty stack. */
    public static FluidStack parseOptional(HolderLookup.Provider registries, CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return FluidStack.EMPTY;
        return FluidStack.OPTIONAL_CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag)
                .resultOrPartial(error -> LegacyLog.warnOnce("fluid stack parse", "An old mod's saved fluid stack could not be read: " + error))
                .orElse(FluidStack.EMPTY);
    }

    /** NeoForge 1.21.1's {@code FluidStack.parse(registries, tag)}. */
    public static Optional<FluidStack> parse(HolderLookup.Provider registries, Tag tag) {
        return FluidStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag)
                .resultOrPartial(error -> LegacyLog.warnOnce("fluid stack parse", "An old mod's saved fluid stack could not be read: " + error));
    }

    /** NeoForge 1.21.1's {@code stack.saveOptional(registries)}: an empty stack saves as an empty tag. */
    public static Tag saveOptional(FluidStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) return new CompoundTag();
        return save(stack, registries);
    }

    /** NeoForge 1.21.1's {@code stack.save(registries)}, which refused an empty stack. */
    public static Tag save(FluidStack stack, HolderLookup.Provider registries) {
        return FluidStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
    }
}
