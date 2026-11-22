/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.feature.configurations;

import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.featuresize.FeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.rootplacers.RootPlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer;

import java.util.List;
import java.util.Optional;

/** The tree configuration 26.3 deleted, kept as old mods read it: public fields a mod's own tree feature uses. */
public class LegacyTreeConfiguration implements FeatureConfiguration {

    public final BlockStateProvider trunkProvider;
    public final TrunkPlacer trunkPlacer;
    public final BlockStateProvider foliageProvider;
    public final FoliagePlacer foliagePlacer;
    public final Optional<RootPlacer> rootPlacer;
    public final BlockStateProvider dirtProvider;
    public final FeatureSize minimumSize;
    public final List<TreeDecorator> decorators;
    public final boolean ignoreVines;
    public final boolean forceDirt;
    public final BlockStateProvider belowTrunkProvider;

    /** The 1.20 to 1.21 constructor; subclasses that place their own trees pass null placers. */
    public LegacyTreeConfiguration(BlockStateProvider trunkProvider, TrunkPlacer trunkPlacer, BlockStateProvider foliageProvider,
            FoliagePlacer foliagePlacer, Optional<RootPlacer> rootPlacer, BlockStateProvider dirtProvider, FeatureSize minimumSize,
            List<TreeDecorator> decorators, boolean ignoreVines, boolean forceDirt) {
        this.trunkProvider = trunkProvider;
        this.trunkPlacer = trunkPlacer;
        this.foliageProvider = foliageProvider;
        this.foliagePlacer = foliagePlacer;
        this.rootPlacer = rootPlacer == null ? Optional.empty() : rootPlacer;
        this.dirtProvider = dirtProvider;
        this.minimumSize = minimumSize;
        this.decorators = decorators == null ? List.of() : decorators;
        this.ignoreVines = ignoreVines;
        this.forceDirt = forceDirt;
        this.belowTrunkProvider = dirtProvider;
    }

    /** The 26.1 and 26.2 constructor, where the dirt provider became the below-trunk provider. */
    public LegacyTreeConfiguration(BlockStateProvider trunkProvider, TrunkPlacer trunkPlacer, BlockStateProvider foliageProvider,
            FoliagePlacer foliagePlacer, Optional<RootPlacer> rootPlacer, FeatureSize minimumSize, List<TreeDecorator> decorators,
            boolean ignoreVines, BlockStateProvider belowTrunkProvider) {
        this(trunkProvider, trunkPlacer, foliageProvider, foliagePlacer, rootPlacer, belowTrunkProvider, minimumSize, decorators,
                ignoreVines, false);
    }
}
