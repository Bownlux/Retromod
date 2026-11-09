/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.feature.stateproviders;

import com.retromod.legacyapi.minecraft.world.level.levelgen.LegacyTypeCodec;
import java.util.function.Supplier;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/** The block state provider type 26.3 removed, rebuilt as the codec 26.3 registers for the provider. */
public final class LegacyBlockStateProviderType<P extends BlockStateProvider> extends LegacyTypeCodec<P> {

    public LegacyBlockStateProviderType(Supplier<?> source) {
        super(source);
    }
}
