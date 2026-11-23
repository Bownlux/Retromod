/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

/** {@code FriendlyByteBuf.readAnySizeNbt()}, which later versions replaced with a read that takes a size limit. */
public final class LegacyNbtReads {

    private LegacyNbtReads() {}

    /** Reads a compound with no size limit; any other tag, or none, reads as null, as it did before. */
    public static CompoundTag readAnySizeNbt(FriendlyByteBuf buf) {
        Tag tag = buf.readNbt(NbtAccounter.unlimitedHeap());
        return tag instanceof CompoundTag compound ? compound : null;
    }
}
