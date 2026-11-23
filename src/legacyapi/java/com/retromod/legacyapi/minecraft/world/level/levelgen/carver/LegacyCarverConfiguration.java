/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.carver;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

/** Stands in for the carver configurations 26.3 deleted, so an old carver class can still name them and load. */
public final class LegacyCarverConfiguration {

    /** One shared value: the old settings are not read, since nothing carves with them. */
    public static final LegacyCarverConfiguration NONE = new LegacyCarverConfiguration();

    public static final Codec<LegacyCarverConfiguration> CODEC = MapCodec.unit(NONE).codec();

    private LegacyCarverConfiguration() {
    }
}
