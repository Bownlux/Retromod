/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Old block, item and fluid render layer calls on 26.x, where the host picks each layer itself. */
public final class LegacyRenderLayers {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApi");

    // A put with no layer (a server, where no render type exists) is still recorded.
    private static final Object NO_LAYER = new Object();

    private static final Map<Object, Object> LAYERS = new ConcurrentHashMap<>();
    private static final AtomicBoolean EXPLAINED = new AtomicBoolean();

    private LegacyRenderLayers() {}

    // ---- The 1.21.6 to 1.21.11 static BlockRenderLayerMap, and the old INSTANCE methods ---

    /** Old {@code putBlock(Block, layer)}. */
    public static void putBlock(Object block, Object layer) {
        record(block, layer);
    }

    /** Old {@code putBlocks(layer, Block...)}. */
    public static void putBlocks(Object layer, Object[] blocks) {
        recordAll(layer, blocks);
    }

    /** Old {@code putItem(Item, RenderType)}. */
    public static void putItem(Object item, Object layer) {
        record(item, layer);
    }

    /** Old {@code putItems(RenderType, Item...)}. */
    public static void putItems(Object layer, Object[] items) {
        recordAll(layer, items);
    }

    /** Old {@code putFluid(Fluid, layer)}. */
    public static void putFluid(Object fluid, Object layer) {
        record(fluid, layer);
    }

    /** Old {@code putFluids(layer, Fluid...)}. */
    public static void putFluids(Object layer, Object[] fluids) {
        recordAll(layer, fluids);
    }

    /** Whether an old call gave {@code blockItemOrFluid} a layer. */
    public static boolean hasLayer(Object blockItemOrFluid) {
        return blockItemOrFluid != null && LAYERS.containsKey(blockItemOrFluid);
    }

    /** The layer an old call gave {@code blockItemOrFluid}, or null when none or unknown. */
    public static Object layerOf(Object blockItemOrFluid) {
        Object layer = blockItemOrFluid == null ? null : LAYERS.get(blockItemOrFluid);
        return layer == NO_LAYER ? null : layer;
    }

    private static void recordAll(Object layer, Object[] targets) {
        if (targets == null) return;
        for (Object target : targets) record(target, layer);
    }

    private static void record(Object target, Object layer) {
        if (target == null) return;
        LAYERS.put(target, layer == null ? NO_LAYER : layer);
        // 26.1 and newer bake each quad into the solid, cutout or translucent section layer from
        // the alpha of its texture (FaceBakery.computeMaterialTransparency, and FluidModel for
        // fluids), so the old mapping is kept for reference and changes nothing on the host.
        if (EXPLAINED.compareAndSet(false, true)) {
            LOGGER.debug("Old render layer calls are recorded only: this host picks each block, "
                    + "item and fluid layer from its texture.");
        }
    }
}
