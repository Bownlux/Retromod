/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Chunk, spawn and villager members 1.21.5 to 26.2 reshaped, rebuilt for mods that call the old ones. */
public final class LegacyWorldgenMembers {

    // The update flag that tells a chunk the block moved with a piston.
    private static final int MOVED_BY_PISTON = 64;

    private LegacyWorldgenMembers() {}

    /** {@code ChunkAccess.setBlockState(pos, state, moved)}: the moved flag became one bit of the update flags. */
    public static BlockState setBlockState(ChunkAccess chunk, BlockPos pos, BlockState state, boolean moved) {
        return chunk.setBlockState(pos, state, moved ? MOVED_BY_PISTON : 0);
    }

    /** A codec constant that became a map codec, read as the codec it wraps. */
    public static Codec<?> codecOf(MapCodec<?> codec) {
        return codec.codec();
    }

    /** A villager type constant, which became the type's registry key. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static VillagerType villagerType(ResourceKey key) {
        return BuiltInRegistries.VILLAGER_TYPE.getValueOrThrow(key);
    }
}
