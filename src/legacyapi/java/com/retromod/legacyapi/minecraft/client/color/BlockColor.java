/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.color;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Minecraft's old block color handler, which old Forge and NeoForge mods implement; 26.1 replaced it with tint sources. */
@FunctionalInterface
public interface BlockColor {

    /** The color for one tint index; -1 leaves the quad untinted. The level and position are null outside a world. */
    int getColor(BlockState state, BlockAndTintGetter level, BlockPos pos, int tintIndex);
}
