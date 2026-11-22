/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.carver;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.CarverOutput;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

import java.util.stream.Stream;

/**
 * The base an old mod's carver class moves onto: a carver type that 26.3's registry accepts, whose carvers carve
 * nothing. The old carving engine those classes tuned is gone in 26.3, so the mod's caves are not generated.
 */
public class LegacyCarverType extends MapCodec<WorldCarver> {

    public LegacyCarverType(Codec<?> configCodec) {
    }

    public LegacyCarverType() {
    }

    @Override
    public <T> Stream<T> keys(DynamicOps<T> ops) {
        return Stream.empty();
    }

    // Any configuration reads as a carver that does nothing, so biomes that name it still load.
    @Override
    public <T> DataResult<WorldCarver> decode(DynamicOps<T> ops, MapLike<T> input) {
        LegacyLog.warnOnce("legacy-carver:" + getClass().getName(), "The old carver " + getClass().getName()
                + " needs the cave carving engine Minecraft 26.3 removed, so it carves nothing. A version of the mod"
                + " built for 26.3 is needed for its caves.");
        return DataResult.success(new Inert(this));
    }

    @Override
    public <T> RecordBuilder<T> encode(WorldCarver input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
        return prefix;
    }

    /** A carver of an old type: never starts, never carves. */
    private record Inert(LegacyCarverType type) implements WorldCarver {
        @Override
        public boolean carve(WorldGenerationContext context, RandomSource random, ChunkPos chunk, ChunkPos source, CarverOutput output) {
            return false;
        }

        @Override
        public boolean isStartChunk(RandomSource random) {
            return false;
        }

        @Override
        public MapCodec<? extends WorldCarver> codec() {
            return type;
        }
    }
}
