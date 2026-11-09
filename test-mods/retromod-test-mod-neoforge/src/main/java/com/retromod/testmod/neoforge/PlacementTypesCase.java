/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.mojang.serialization.MapCodec;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProviderType;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Farmer's Delight, Lithostitched and YUNG's register placement modifier and block state provider types and extend
 * their bases, which 26.3 turned into interfaces whose types are bare codecs. The types must register, and the old
 * classes must load and place as before.
 */
final class PlacementTypesCase {

    private static final String MOD_ID = "retromod_test_mod_neoforge";

    static final DeferredRegister<PlacementModifierType<?>> MODIFIERS = DeferredRegister.create(Registries.PLACEMENT_MODIFIER_TYPE, MOD_ID);
    static final DeferredHolder<PlacementModifierType<?>, PlacementModifierType<KeepFilter>> KEEP =
            MODIFIERS.register("legacy_keep", () -> () -> KeepFilter.CODEC);
    static final DeferredHolder<PlacementModifierType<?>, PlacementModifierType<Doubled>> DOUBLED =
            MODIFIERS.register("legacy_doubled", () -> () -> Doubled.CODEC);
    static final DeferredRegister<BlockStateProviderType<?>> PROVIDERS = DeferredRegister.create(Registries.BLOCK_STATE_PROVIDER_TYPE, MOD_ID);
    static final DeferredHolder<BlockStateProviderType<?>, BlockStateProviderType<StoneProvider>> STONE =
            PROVIDERS.register("legacy_stone", () -> new BlockStateProviderType<>(StoneProvider.CODEC));

    private PlacementTypesCase() {}

    static void register(IEventBus modBus) {
        MODIFIERS.register(modBus);
        PROVIDERS.register(modBus);
    }

    /** The registered types read back, and the old classes place and pick as their code says. */
    static String run() {
        RandomSource random = RandomSource.create(7);
        if (new KeepFilter().getPositions(null, random, BlockPos.ZERO).count() != 1
                || new Doubled().getPositions(null, random, BlockPos.ZERO).count() != 2) {
            return "fail: the old modifiers place the wrong number of positions";
        }
        if (new KeepFilter().type() != KEEP.get() || BuiltInRegistries.PLACEMENT_MODIFIER_TYPE.getKey(KEEP.get()) == null) {
            return "fail: the filter's type is not the registered one";
        }
        if (new StoneProvider().getState(random, BlockPos.ZERO) != Blocks.STONE.defaultBlockState()) {
            return "fail: the old provider picks the wrong state";
        }
        return BuiltInRegistries.BLOCKSTATE_PROVIDER_TYPE.getKey(STONE.get()) != null
                ? "success" : "fail: the provider type is not registered";
    }

    /** A filter that keeps every position, with a unit codec. */
    static final class KeepFilter extends PlacementFilter {
        static final MapCodec<KeepFilter> CODEC = MapCodec.unit(KeepFilter::new);

        @Override
        protected boolean shouldPlace(PlacementContext context, RandomSource random, BlockPos pos) {
            return true;
        }

        @Override
        public PlacementModifierType<?> type() {
            return KEEP.get();
        }
    }

    /** A modifier that places twice where it is given. */
    static final class Doubled extends PlacementModifier {
        static final MapCodec<Doubled> CODEC = MapCodec.unit(Doubled::new);

        @Override
        public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos pos) {
            return Stream.of(pos, pos);
        }

        @Override
        public PlacementModifierType<?> type() {
            return DOUBLED.get();
        }
    }

    /** A provider that always picks stone. */
    static final class StoneProvider extends BlockStateProvider {
        static final MapCodec<StoneProvider> CODEC = MapCodec.unit(StoneProvider::new);

        @Override
        protected BlockStateProviderType<?> type() {
            return STONE.get();
        }

        @Override
        public BlockState getState(RandomSource random, BlockPos pos) {
            return Blocks.STONE.defaultBlockState();
        }
    }
}
