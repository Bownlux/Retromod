/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.legacyapi.minecraft.client.model.ChunkRenderTypeSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraftforge.client.RenderTypeGroup;

/** Forge 1.20.1's RenderTypeGroup constructors and {@code block()}, whose block member Forge 26.2 made a chunk layer. */
public final class LegacyRenderTypeGroups {

    private LegacyRenderTypeGroups() {}

    public static RenderTypeGroup create(RenderType block, RenderType entity) {
        return new RenderTypeGroup(layer(block), entity, entity);
    }

    public static RenderTypeGroup create(RenderType block, RenderType entity, RenderType entityFabulous) {
        return new RenderTypeGroup(layer(block), entity, entityFabulous);
    }

    /** The group's block layer as the render type old code compares against, or null for an empty group. */
    public static RenderType block(RenderTypeGroup group) {
        ChunkSectionLayer layer = group.block();
        return layer == null ? null : ChunkRenderTypeSet.renderTypeOf(layer);
    }

    // A render type that is no block layer was a mistake Forge 1.20.1 let through; it draws solid here.
    private static ChunkSectionLayer layer(RenderType block) {
        if (block == null) return null;
        ChunkSectionLayer layer = ChunkRenderTypeSet.layerOf(block);
        return layer == null ? ChunkSectionLayer.SOLID : layer;
    }
}
