/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.color;

import com.retromod.shim.common.embedded.LegacyLog;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** One tint index of an old block color, as the host's tint source. */
public final class LegacyBlockTint implements BlockTintSource {

    // Minecraft 26.1 asks a block for the tint source at each index its quads name, and leaves an index past the
    // list untinted. The models of the Forge 1.20.1 and 1.21.1 corpora name tint indexes 0 to 3.
    static final int SLOTS = 4;

    final BlockColor handler;
    private final int index;

    private LegacyBlockTint(BlockColor handler, int index) {
        this.handler = handler;
        this.index = index;
    }

    /** One tint source per tint index for an old handler. */
    public static List<BlockTintSource> sources(BlockColor handler) {
        List<BlockTintSource> sources = new ArrayList<>(SLOTS);
        for (int index = 0; index < SLOTS; index++) sources.add(new LegacyBlockTint(handler, index));
        return List.copyOf(sources);
    }

    @Override
    public int color(BlockState state) {
        // Outside a world the old handler got a null level and position.
        return colorOf(state, null, null);
    }

    @Override
    public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        return colorOf(state, level, pos);
    }

    private int colorOf(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        try {
            // Old block renderers ignored alpha and old handlers returned RGB; Minecraft 26.1 multiplies alpha in.
            // -1 stays -1, no tint.
            return handler.getColor(state, level, pos, index) | 0xFF000000;
        } catch (RuntimeException | LinkageError failure) {
            // Old handlers now run while the game draws, and one can reach a vanilla API a later Minecraft removed.
            LegacyLog.warnOnce("block color " + handler.getClass().getName(), "The old block color handler "
                    + handler.getClass().getName() + " threw " + failure + ", so Retromod draws its tint untinted");
            return -1;
        }
    }

    @Override
    public String toString() {
        return "legacy block color " + index + " of " + handler;
    }
}
