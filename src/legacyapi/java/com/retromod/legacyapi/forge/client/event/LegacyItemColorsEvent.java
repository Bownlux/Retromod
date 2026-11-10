/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.event;

import com.retromod.legacyapi.forge.eventbus.LegacyEvent;
import com.retromod.legacyapi.minecraft.client.color.ItemColor;
import com.retromod.legacyapi.minecraft.client.color.ItemColors;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.world.level.ItemLike;
import net.minecraftforge.eventbus.api.event.InheritableEvent;
import net.minecraftforge.fml.event.IModBusEvent;

/** Forge 1.20.1's mod-bus event for item colors, which Forge 26.2 removed, posted after every block color handler. */
public class LegacyItemColorsEvent extends LegacyEvent implements IModBusEvent, InheritableEvent {

    private final ItemColors itemColors;
    private final BlockColors blockColors;

    public LegacyItemColorsEvent(ItemColors itemColors, BlockColors blockColors) {
        this.itemColors = itemColors;
        this.blockColors = blockColors;
    }

    public ItemColors getItemColors() {
        return itemColors;
    }

    /** The host's block colors, which every block color handler has already filled. */
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
