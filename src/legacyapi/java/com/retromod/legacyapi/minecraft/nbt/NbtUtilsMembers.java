/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.nbt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;

import java.util.Optional;
import java.util.UUID;

/** The {@code NbtUtils} UUID and block position members 1.21.5 removed, writing the formats old saves hold. */
public final class NbtUtilsMembers {

    private NbtUtilsMembers() {}

    /** {@code NbtUtils.createUUID(uuid)}: the UUID as an array of four ints. */
    public static IntArrayTag createUUID(UUID uuid) {
        return new IntArrayTag(UUIDUtil.uuidToIntArray(uuid));
    }

    /** {@code NbtUtils.loadUUID(tag)}: a tag of any other shape fails, as it did before. */
    public static UUID loadUUID(Tag tag) {
        if (!(tag instanceof IntArrayTag array)) {
            throw new IllegalArgumentException("Expected a UUID tag of four ints, but found " + tag);
        }
        int[] ints = array.getAsIntArray();
        if (ints.length != 4) {
            throw new IllegalArgumentException("Expected a UUID array of length 4, but found length " + ints.length);
        }
        return UUIDUtil.uuidFromIntArray(ints);
    }

    /** {@code NbtUtils.readBlockPos(tag)} from before 1.20.5: the {@code X}, {@code Y} and {@code Z} ints, 0 when missing. */
    public static BlockPos readBlockPos(CompoundTag tag) {
        return new BlockPos(tag.getIntOr("X", 0), tag.getIntOr("Y", 0), tag.getIntOr("Z", 0));
    }

    /** {@code NbtUtils.writeBlockPos(pos)} from before 1.20.5: a compound with {@code X}, {@code Y} and {@code Z}. */
    public static CompoundTag writeBlockPos(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        return tag;
    }

    /** {@code NbtUtils.readBlockPos(tag, key)} from 1.20.5: three ints in an array, else nothing. */
    public static Optional<BlockPos> readBlockPos(CompoundTag tag, String key) {
        int[] ints = tag.get(key) instanceof IntArrayTag array ? array.getAsIntArray() : null;
        return ints != null && ints.length == 3 ? Optional.of(new BlockPos(ints[0], ints[1], ints[2])) : Optional.empty();
    }

    /** {@code NbtUtils.writeBlockPos(pos)} from 1.20.5, which returned a tag: the position as an array of three ints. */
    public static Tag writeBlockPosArray(BlockPos pos) {
        return new IntArrayTag(new int[]{pos.getX(), pos.getY(), pos.getZ()});
    }
}
