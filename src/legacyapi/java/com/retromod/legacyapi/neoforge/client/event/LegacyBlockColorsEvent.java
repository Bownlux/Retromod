/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import com.retromod.legacyapi.minecraft.client.color.BlockColor;
import com.retromod.legacyapi.minecraft.client.color.LegacyBlockTint;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

/** NeoForge 1.21.1's block color event, which NeoForge 26.1 replaced; Retromod posts it while NeoForge registers block tints. */
public class LegacyBlockColorsEvent extends Event implements IModBusEvent {

    private final BlockColors blockColors;

    public LegacyBlockColorsEvent(BlockColors blockColors) {
        this.blockColors = blockColors;
    }

    /** The host's block colors, which old mods also read and register on directly. */
    public BlockColors getBlockColors() {
        return blockColors;
    }

    /** {@code register(BlockColor, Block...)}: one tint source per tint index for the old handler. */
    public void register(BlockColor color, Block... blocks) {
        blockColors.register(LegacyBlockTint.sources(color), blocks);
    }
}
