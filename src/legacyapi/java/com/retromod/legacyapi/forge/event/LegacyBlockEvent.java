/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.event;

import com.retromod.legacyapi.forge.eventbus.LegacyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.BlockEvent;

/** Forge 1.20.1's {@code BlockEvent} class, rebuilt for mod events that extend it, such as Moonlight's block events. */
public class LegacyBlockEvent extends LegacyEvent implements BlockEvent {

    private final LevelAccessor level;
    private final BlockPos pos;
    private final BlockState state;

    public LegacyBlockEvent(LevelAccessor level, BlockPos pos, BlockState state) {
        this.level = level;
        this.pos = pos;
        this.state = state;
    }

    @Override
    public LevelAccessor getLevel() {
        return level;
    }

    @Override
    public BlockPos getPos() {
        return pos;
    }

    @Override
    public BlockState getState() {
        return state;
    }
}
