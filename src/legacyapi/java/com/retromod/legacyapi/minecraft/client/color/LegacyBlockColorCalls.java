/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.color;

import java.util.List;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** The old BlockColors calls, which old mods make on the host's block colors. */
public final class LegacyBlockColorCalls {

    private LegacyBlockColorCalls() {}

    /** {@code BlockColors.register(BlockColor, Block...)}. */
    public static void register(BlockColors colors, BlockColor color, Block... blocks) {
        colors.register(LegacyBlockTint.sources(color), blocks);
    }

    /** {@code BlockColors.getColor(state, level, pos, tintIndex)} as 1.21 answered it: the handler's color with its own alpha, or -1. */
    public static int getColor(BlockColors colors, BlockState state, BlockAndTintGetter level, BlockPos pos, int tintIndex) {
        return colorOf(colors, state, level, pos, tintIndex);
    }

    /** The same call for a mod built before 1.21, which got the block's RGB color, or -1 when it has none. */
    public static int getColorRgb(BlockColors colors, BlockState state, BlockAndTintGetter level, BlockPos pos, int tintIndex) {
        return rgb(colorOf(colors, state, level, pos, tintIndex));
    }

    private static int colorOf(BlockColors colors, BlockState state, BlockAndTintGetter level, BlockPos pos, int tintIndex) {
        List<BlockTintSource> sources = colors.getTintSources(state);
        if (sources.isEmpty()) return -1;
        // An old handler from this copy answers itself for any index, as it did before.
        if (sources.get(0) instanceof LegacyBlockTint own) return own.handler.getColor(state, level, pos, tintIndex);
        if (tintIndex < 0 || tintIndex >= sources.size()) return -1;
        BlockTintSource source = sources.get(tintIndex);
        if (source == null) return -1;
        return level == null || pos == null ? source.color(state) : source.colorInWorld(state, level, pos);
    }

    // Before 1.21 the call answered RGB, and Minecraft 26.1's tint sources answer ARGB; -1, no color, stays -1.
    private static int rgb(int color) {
        return color == -1 ? -1 : color & 0xFFFFFF;
    }
}
