/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import com.retromod.legacyapi.minecraft.client.color.ItemColor;
import com.retromod.legacyapi.minecraft.client.color.ItemColors;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.world.level.ItemLike;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

/** NeoForge 1.21.1's item color event, which NeoForge 1.21.4 removed; Retromod posts it after every old block color handler. */
public class LegacyItemColorsEvent extends Event implements IModBusEvent {

    private final ItemColors itemColors;
    private final BlockColors blockColors;

    public LegacyItemColorsEvent(ItemColors itemColors, BlockColors blockColors) {
        this.itemColors = itemColors;
        this.blockColors = blockColors;
    }

    public ItemColors getItemColors() {
        return itemColors;
    }

    /** The host's block colors, which every old block color handler has already filled. */
    public BlockColors getBlockColors() {
        return blockColors;
    }

    public void register(ItemColor color, ItemLike... items) {
        itemColors.register(color, items);
    }

    /** {@code register} for a mod built before 1.21, whose item renderer drew every tint opaque. */
    public void registerOpaque(ItemColor color, ItemLike... items) {
        itemColors.registerOpaque(color, items);
    }
}
