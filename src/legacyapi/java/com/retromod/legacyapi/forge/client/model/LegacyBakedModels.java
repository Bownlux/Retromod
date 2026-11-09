/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.legacyapi.minecraft.client.model.ChunkRenderTypeSet;
import com.retromod.legacyapi.minecraft.client.model.LegacyChunkLayers;
import java.util.List;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.level.block.state.BlockState;

/** The answers Forge 1.20.1's BakedModel gave by default, read from Minecraft 26.2's models. */
final class LegacyBakedModels {

    private LegacyBakedModels() {}

    /** Forge 1.20.1's {@code ItemBlockRenderTypes.getRenderLayers(state)}, read without the live model. */
    static ChunkRenderTypeSet defaultRenderTypes(BlockState state) {
        // An old setRenderLayer choice, else the layers of the model the block had before an old model replaced it.
        // The live model is not read, since the old model asking may be that model.
        if (state == null) return ChunkRenderTypeSet.ofLayers(ChunkSectionLayer.SOLID);
        ChunkRenderTypeSet chosen = LegacyChunkLayers.recorded(state.getBlock());
        if (chosen != null) return chosen;
        int bits = LegacyModelMaps.originalLayers(state);
        return bits == 0 ? ChunkRenderTypeSet.ofLayers(ChunkSectionLayer.SOLID) : ChunkRenderTypeSet.ofBits(bits);
    }

    /** Forge 1.20.1's item render type fallback; Minecraft 26.2 picks each quad's own from its texture. */
    static List<RenderType> defaultItemRenderTypes() {
        return List.of(Sheets.translucentItemSheet());
    }
}
