/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121.tests;

import com.mojang.serialization.MapCodec;
import com.retromod.testmod121.RetromodTestMod121;
import com.retromod.testmod121.Test;
import com.retromod.testmod121.TestResult;
import java.util.stream.Stream;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.gen.feature.FeaturePlacementContext;
import net.minecraft.world.gen.placementmodifier.AbstractConditionalPlacementModifier;
import net.minecraft.world.gen.placementmodifier.PlacementModifier;
import net.minecraft.world.gen.placementmodifier.PlacementModifierType;
import net.minecraft.world.gen.stateprovider.BlockStateProvider;
import net.minecraft.world.gen.stateprovider.BlockStateProviderType;

/**
 * Lithostitched, Farmer's Delight Refabricated and Waystones extend the placement modifier and block state provider
 * bases and register modifier types, which 26.3 turned into interfaces and bare codecs. The type must register, and
 * the old classes must load and place as before.
 */
public final class Test85OldPlacementTypes implements Test {

    static final PlacementModifierType<KeepFilter> KEEP = () -> KeepFilter.CODEC;

    /** Registers the modifier type while the mod initializes, as the mods do. */
    public static void registerType() {
        Registry.register(Registries.PLACEMENT_MODIFIER_TYPE, Identifier.of(RetromodTestMod121.MOD_ID, "legacy_keep"), KEEP);
    }

    @Override
    public String description() {
        return "1.21.1 placement modifiers and a block state provider on the bases 26.3 made interfaces register, place and pick";
    }

    @Override
    public TestResult run() {
        Random random = Random.create(7);
        if (new KeepFilter().getPositions(null, random, BlockPos.ORIGIN).count() != 1
                || new Doubled().getPositions(null, random, BlockPos.ORIGIN).count() != 2) {
            return TestResult.fail("the old modifiers place the wrong number of positions");
        }
        if (new KeepFilter().getType() != KEEP || Registries.PLACEMENT_MODIFIER_TYPE.getId(KEEP) == null) {
            return TestResult.fail("the filter's type is not the registered one");
        }
        return new StoneProvider().get(random, BlockPos.ORIGIN) == Blocks.STONE.getDefaultState()
                ? TestResult.success() : TestResult.fail("the old provider picks the wrong state");
    }

    /** A filter that keeps every position, with a unit codec. */
    static final class KeepFilter extends AbstractConditionalPlacementModifier {
        static final MapCodec<KeepFilter> CODEC = MapCodec.unit(KeepFilter::new);

        @Override
        protected boolean shouldPlace(FeaturePlacementContext context, Random random, BlockPos pos) {
            return true;
        }

        @Override
        public PlacementModifierType<?> getType() {
            return KEEP;
        }
    }

    /** A modifier that places twice where it is given. */
    static final class Doubled extends PlacementModifier {
        @Override
        public Stream<BlockPos> getPositions(FeaturePlacementContext context, Random random, BlockPos pos) {
            return Stream.of(pos, pos);
        }

        @Override
        public PlacementModifierType<?> getType() {
            return KEEP;
        }
    }

    /** A provider that always picks stone; its type is never asked for here. */
    static final class StoneProvider extends BlockStateProvider {
        @Override
        protected BlockStateProviderType<?> getType() {
            return null;
        }

        @Override
        public BlockState get(Random random, BlockPos pos) {
            return Blocks.STONE.getDefaultState();
        }
    }
}
