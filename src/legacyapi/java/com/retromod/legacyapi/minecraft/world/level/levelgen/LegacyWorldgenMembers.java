/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Chunk, biome, noise and villager members newer hosts reshaped, rebuilt for mods that call the old ones. */
public final class LegacyWorldgenMembers {

    // The update flag that tells a chunk the block moved with a piston.
    private static final int MOVED_BY_PISTON = 64;

    private LegacyWorldgenMembers() {}

    // One climate sampler per random state, as 1.21.1 kept in a field. 26.3 builds a new one on every call, but each
    // wraps the samplers the state's compiler already caches and the shared uncached context, so a kept one is the same.
    private static final Map<RandomState, Climate.Sampler> SAMPLERS = new WeakHashMap<>();
    // A sampler may keep its state alive, so the map is emptied before it could hold many worlds' states.
    private static final int SAMPLERS_KEPT = 16;
    // The last state and its sampler, held weakly so an unloaded world is not kept alive by this shortcut.
    private static volatile WeakReference<?>[] lastSampler;

    /** {@code RandomState.sampler()}: 26.3 builds a climate sampler per context, and an uncached one gives the same values. */
    public static Climate.Sampler sampler(RandomState state) {
        // Most calls come from one world in a row, so the last answer is checked without the lock.
        WeakReference<?>[] last = lastSampler;
        if (last != null && last[0].get() == state && last[1].get() instanceof Climate.Sampler kept) return kept;
        Climate.Sampler sampler;
        synchronized (SAMPLERS) {
            sampler = SAMPLERS.get(state);
            if (sampler == null) {
                if (SAMPLERS.size() >= SAMPLERS_KEPT) SAMPLERS.clear();
                sampler = state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
                SAMPLERS.put(state, sampler);
            }
        }
        lastSampler = new WeakReference<?>[] {new WeakReference<>(state), new WeakReference<>(sampler)};
        return sampler;
    }

    /** {@code BiomeSource.getNoiseBiome(x, y, z, sampler)}: 26.3 answers it from the resolver a source builds for a sampler. */
    public static Holder<?> getNoiseBiome(BiomeSource source, int x, int y, int z, Climate.Sampler sampler) {
        return source.createResolver(sampler).getNoiseBiome(x, y, z);
    }

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
