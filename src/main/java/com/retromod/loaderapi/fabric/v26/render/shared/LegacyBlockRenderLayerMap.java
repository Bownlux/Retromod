/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.render.shared;

import com.retromod.loaderapi.fabric.v26.render.LegacyRenderLayers;

/** The old Fabric API {@code blockrenderlayer.v1.BlockRenderLayerMap}, one type for every mod. */
public interface LegacyBlockRenderLayerMap {

    /** The old {@code BlockRenderLayerMap.INSTANCE}. */
    LegacyBlockRenderLayerMap INSTANCE = new LegacyBlockRenderLayerMap() { };

    // Default methods, so a mod class that implemented the old interface still loads.

    /** Old {@code putBlock(Block, RenderType)}. */
    default void putBlock(Object block, Object layer) {
        LegacyRenderLayers.putBlock(block, layer);
    }

    /** Old {@code putBlocks(RenderType, Block...)}. */
    default void putBlocks(Object layer, Object[] blocks) {
        LegacyRenderLayers.putBlocks(layer, blocks);
    }

    /** Old {@code putItem(Item, RenderType)}. */
    default void putItem(Object item, Object layer) {
        LegacyRenderLayers.putItem(item, layer);
    }

    /** Old {@code putItems(RenderType, Item...)}. */
    default void putItems(Object layer, Object[] items) {
        LegacyRenderLayers.putItems(layer, items);
    }

    /** Old {@code putFluid(Fluid, RenderType)}. */
    default void putFluid(Object fluid, Object layer) {
        LegacyRenderLayers.putFluid(fluid, layer);
    }

    /** Old {@code putFluids(RenderType, Fluid...)}. */
    default void putFluids(Object layer, Object[] fluids) {
        LegacyRenderLayers.putFluids(layer, fluids);
    }
}
