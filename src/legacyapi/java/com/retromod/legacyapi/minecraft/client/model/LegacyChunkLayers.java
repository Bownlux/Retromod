/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.model;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.polyfill.minecraft.RetroItemBlockRenderTypes;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Forge 1.20.1's and NeoForge 1.21.1's ItemBlockRenderTypes.getRenderLayers and its ChunkRenderTypeSet setter. */
public final class LegacyChunkLayers {

    // Minecraft 26.x picks each quad's layer from its texture when it bakes the model, so it has no block table.
    // Old setRenderLayer calls are recorded in one table every copy shares, which RetroItemBlockRenderTypes keeps
    // with ChunkRenderTypeSet's layer bits.

    /** The slot of the per-thread mark that says this thread is reading a host model for its layers. */
    static final String READING = "forge.renderLayers.reading";
    static final int PROTOCOL = 1;

    private static volatile ThreadLocal<Object> reading;

    private LegacyChunkLayers() {}

    /** The layers an old call chose for the block, or else the layer of the host's own model for this state. */
    public static ChunkRenderTypeSet getRenderLayers(BlockState state) {
        if (state == null) return ChunkRenderTypeSet.ofLayers(ChunkSectionLayer.SOLID);
        ChunkRenderTypeSet chosen = recorded(state.getBlock());
        if (chosen != null) return chosen;
        // The host's model for a state can be an adapter over an old model that asks for these layers again while
        // the host reads it. A nested ask on the same thread, from any mod's copy, answers solid instead of looping.
        ThreadLocal<Object> mark = reading();
        if (mark.get() != null) return ChunkRenderTypeSet.ofLayers(ChunkSectionLayer.SOLID);
        Object derived;
        mark.set(Boolean.TRUE);
        try {
            derived = RetroItemBlockRenderTypes.getChunkRenderType(state);
        } finally {
            mark.remove();
        }
        ChunkSectionLayer layer = derived instanceof RenderType type ? ChunkRenderTypeSet.layerOf(type) : null;
        return ChunkRenderTypeSet.ofLayers(layer == null ? ChunkSectionLayer.SOLID : layer);
    }

    /**
     * The layers an old setRenderLayer call chose for the block, or null when none did. A model that is itself the
     * host's model for a block asks this, not {@link #getRenderLayers}, which would read that model back.
     */
    public static ChunkRenderTypeSet recorded(Block block) {
        int bits = RetroItemBlockRenderTypes.recordedLayers(block);
        return bits == 0 ? null : ChunkRenderTypeSet.ofBits(bits);
    }

    public static void setRenderLayer(Block block, ChunkRenderTypeSet layers) {
        if (block != null && layers != null && !layers.isEmpty()) {
            RetroItemBlockRenderTypes.recordLayers(block, layers.bits());
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Object> reading() {
        ThreadLocal<Object> mark = reading;
        if (mark == null) {
            // Shared by every copy, so a loop that passes through two mods' copies stops too.
            mark = LegacySharedSlots.get(READING, PROTOCOL, ThreadLocal.class, ThreadLocal::new);
            reading = mark;
        }
        return mark;
    }
}
