/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.color;

import com.retromod.legacyapi.minecraft.client.color.BlockColor;
import com.retromod.legacyapi.minecraft.client.color.LegacyBlockTint;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;

/** Forge 1.20.1's block color registration on the host's block color event, which now takes tint sources. */
public final class LegacyBlockColors {

    private LegacyBlockColors() {}

    /** {@code RegisterColorHandlersEvent.Block.register(BlockColor, Block...)}. */
    public static void register(RegisterColorHandlersEvent.Block event, BlockColor color, Block... blocks) {
        event.register(LegacyBlockTint.sources(color), blocks);
    }
}
