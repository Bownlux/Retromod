/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.IQuadTransformer;
import net.minecraftforge.client.model.QuadTransformers;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.LoggerFactory;

/** Forge 1.20.1's chunk render type sets, block layers and int[] quads, which Forge 26.2 removed, checked at client setup. Client only. */
final class ModelFoundationCases {

    private ModelFoundationCases() {}

    static void register(IEventBus modBus) {
        modBus.addListener(ModelFoundationCases::onClientSetup);
    }

    // Render types load safely on the render thread at client setup, where 1.20.1 mods set their block layers.
    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            log("ChunkRenderTypeSet.of(RenderType.cutout()) holds cutout, and cutout mipped counts as cutout",
                    ModelFoundationCases::cutoutSet);
            log("ItemBlockRenderTypes.setRenderLayer is read back by getRenderLayers", ModelFoundationCases::layerReadBack);
            log("An old BakedQuad keeps its int[], and QuadTransformers.settingEmissivity reaches its light",
                    ModelFoundationCases::oldQuad);
        });
    }

    private static boolean cutoutSet() {
        ChunkRenderTypeSet set = ChunkRenderTypeSet.of(RenderType.cutout());
        return set.contains(RenderType.cutout()) && !set.contains(RenderType.solid())
                && ChunkRenderTypeSet.of(RenderType.cutoutMipped()).contains(RenderType.cutout());
    }

    private static boolean layerReadBack() {
        ItemBlockRenderTypes.setRenderLayer(CrossClassBlocks.CROSS_CLASS_BLOCK, RenderType.translucent());
        return ItemBlockRenderTypes.getRenderLayers(CrossClassBlocks.CROSS_CLASS_BLOCK.defaultBlockState())
                .contains(RenderType.translucent());
    }

    private static boolean oldQuad() {
        int[] vertices = new int[IQuadTransformer.STRIDE * 4];
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * IQuadTransformer.STRIDE;
            vertices[base + IQuadTransformer.POSITION] = Float.floatToRawIntBits(vertex == 2 || vertex == 3 ? 1 : 0);
            vertices[base + IQuadTransformer.POSITION + 1] = Float.floatToRawIntBits(1);
            vertices[base + IQuadTransformer.POSITION + 2] = Float.floatToRawIntBits(vertex == 1 || vertex == 2 ? 1 : 0);
            vertices[base + IQuadTransformer.COLOR] = -1;
        }
        BakedQuad quad = new BakedQuad(vertices, -1, Direction.UP, null, true);
        BakedQuad glowing = QuadTransformers.settingEmissivity(5).process(quad);
        int light = glowing.getVertices()[IQuadTransformer.UV2];
        return quad.getVertices() == vertices && quad.getDirection() == Direction.UP && (light & 0xFFFF) >> 4 == 5;
    }

    private interface Case { boolean run() throws Throwable; }

    private static void log(String what, Case body) {
        String result;
        try {
            result = body.run() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }
}
