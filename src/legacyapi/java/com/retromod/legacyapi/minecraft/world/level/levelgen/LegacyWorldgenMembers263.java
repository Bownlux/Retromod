/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Biome and noise members 26.3 reshaped, rebuilt for mods that call the old ones; it links only on 26.3 and newer. */
public final class LegacyWorldgenMembers263 {

    private LegacyWorldgenMembers263() {}

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

    /** {@code RandomState.create(settings, noises, seed)}: 26.3 takes the noise lookup and seed first. */
    public static RandomState createRandomState(NoiseGeneratorSettings settings, HolderGetter<?> noises, long seed) {
        return RandomState.create(noises, seed, settings);
    }

    /** {@code random.oreRandom()}: the ore factory is now made on request under the name it was built from. */
    public static PositionalRandomFactory oreRandom(RandomState random) {
        return random.getOrCreateRandomFactory(Identifier.withDefaultNamespace("ore"));
    }

    /** {@code random.aquiferRandom()}, made on request the same way. */
    public static PositionalRandomFactory aquiferRandom(RandomState random) {
        return random.getOrCreateRandomFactory(Identifier.withDefaultNamespace("aquifer"));
    }

    /** {@code source.findBiomeHorizontal(x, y, z, radius, allowed, random, sampler)}: a random match, checking every quart. */
    public static Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(BiomeSource source, int x, int y, int z, int radius,
            Predicate<Holder<Biome>> allowed, RandomSource random, Climate.Sampler sampler) {
        return findBiomeHorizontal(source, x, y, z, radius, 1, allowed, random, false, sampler);
    }

    /**
     * {@code source.findBiomeHorizontal(x, y, z, radius, step, allowed, random, closest, sampler)}: 26.3 takes the random
     * state instead of a sampler, so the search runs here on a resolver built for the sampler the mod passed.
     */
    @SuppressWarnings("unchecked")
    public static Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(BiomeSource source, int x, int y, int z, int radius,
            int step, Predicate<Holder<Biome>> allowed, RandomSource random, boolean closest, Climate.Sampler sampler) {
        BiomeResolver resolver = source.createResolver(sampler);
        // Biomes are stored per quart, four blocks across; the step was already counted in quarts.
        int centerX = x >> 2;
        int quartY = y >> 2;
        int centerZ = z >> 2;
        int reach = radius >> 2;
        int stride = Math.max(1, step);
        Pair<BlockPos, Holder<Biome>> chosen = null;
        int matches = 0;
        // Looking for the closest one walks square rings outward from the center; otherwise the whole square is read once.
        for (int ring = closest ? 0 : reach; ring <= reach; ring += stride) {
            for (int dz = -ring; dz <= ring; dz += stride) {
                boolean edgeRow = Math.abs(dz) == ring;
                for (int dx = -ring; dx <= ring; dx += stride) {
                    if (closest && !edgeRow && Math.abs(dx) != ring) continue;
                    Holder<Biome> biome = (Holder<Biome>) resolver.getNoiseBiome(centerX + dx, quartY, centerZ + dz);
                    if (!allowed.test(biome)) continue;
                    Pair<BlockPos, Holder<Biome>> found = Pair.of(new BlockPos((centerX + dx) << 2, y, (centerZ + dz) << 2), biome);
                    if (closest) return found;
                    // Each later match replaces the kept one with chance 1 in its count, so every match is equally likely.
                    // The first match draws nothing, as the old search did, so a seeded random stays in step.
                    if (chosen == null || random.nextInt(matches + 1) == 0) chosen = found;
                    matches++;
                }
            }
        }
        return chosen;
    }
}
