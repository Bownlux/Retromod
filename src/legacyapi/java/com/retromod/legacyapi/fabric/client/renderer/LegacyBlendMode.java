/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Fabric API's old {@code BlendMode}, in its 1.20 and 1.21 order, mapped onto 26.x's chunk section layers. */
public enum LegacyBlendMode {
    DEFAULT(null),
    SOLID(ChunkSectionLayer.SOLID),
    CUTOUT_MIPPED(ChunkSectionLayer.CUTOUT),
    CUTOUT(ChunkSectionLayer.CUTOUT),
    TRANSLUCENT(ChunkSectionLayer.TRANSLUCENT);

    /** The old field named a 1.21 render type; 26.x keeps layers apart from render types, so it stays empty. */
    public final RenderType blockRenderLayer = null;
    private final ChunkSectionLayer layer;

    LegacyBlendMode(ChunkSectionLayer layer) {
        this.layer = layer;
    }

    /** The section layer a quad with this mode goes to; null for DEFAULT, which leaves the layer to the sprite. */
    public ChunkSectionLayer layer() {
        return layer;
    }

    /** {@code fromRenderLayer(layer)}: old render types are not layers any more, so every one reads as DEFAULT. */
    public static LegacyBlendMode fromRenderLayer(RenderType renderLayer) {
        return DEFAULT;
    }
}
