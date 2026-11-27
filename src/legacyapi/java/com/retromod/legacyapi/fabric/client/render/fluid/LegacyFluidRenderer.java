/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.render.fluid;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Draws a fluid whose old handler names its textures only once they load, or draws it itself, on 26.x's fluid renderer. */
final class LegacyFluidRenderer implements FluidRenderHandler {

    // The renderer of the draw in progress, for an old handler's super.renderFluid.
    private static final ThreadLocal<FluidRenderer> CURRENT = new ThreadLocal<>();

    private final Object handler;
    private volatile Object reloadedAtlas;

    LegacyFluidRenderer(Object handler) {
        this.handler = handler;
    }

    @Override
    public void renderFluid(FluidRenderer renderer, BlockPos pos, BlockAndTintGetter level, FluidRenderer.Output output,
            BlockState blockState, FluidState fluidState) {
        if (LegacyFluidRenders.hasOwnDraw(handler)) {
            // The old handler draws itself into the layer old fluids drew to; a failed draw leaves that layer untouched.
            FluidRenderer previous = CURRENT.get();
            CURRENT.set(renderer);
            try {
                if (LegacyFluidRenders.drawOwn(handler, pos, level, output.getBuilder(ChunkSectionLayer.TRANSLUCENT), blockState,
                        fluidState)) {
                    return;
                }
            } finally {
                CURRENT.set(previous);
            }
        }
        draw(renderer, level, pos, output, blockState, fluidState);
    }

    // The host's fluid geometry, with the stand-in textures swapped for the handler's own one quad at a time.
    private void draw(FluidRenderer renderer, BlockAndTintGetter level, BlockPos pos, FluidRenderer.Output output, BlockState blockState,
            FluidState fluidState) {
        reloadOnce();
        TextureAtlasSprite[] own = LegacyFluidRenders.sprites(handler, level, pos, fluidState);
        TextureAtlasSprite[] standIn = own == null ? null : standInSprites(fluidState);
        if (standIn == null) {
            renderer.tesselate(level, pos, output, blockState, fluidState);
            return;
        }
        List<LegacyFluidQuads> buffers = new ArrayList<>(2);
        renderer.tesselate(level, pos, layer -> {
            LegacyFluidQuads quads = new LegacyFluidQuads(output.getBuilder(layer), standIn, own);
            buffers.add(quads);
            return quads;
        }, blockState, fluidState);
        for (LegacyFluidQuads quads : buffers) quads.commit();
    }

    /** An old handler's {@code super.renderFluid(...)}: the host's geometry with that handler's textures, into its consumer. */
    static void renderDefault(Object handler, BlockPos pos, BlockAndTintGetter level, VertexConsumer consumer, BlockState blockState,
            FluidState fluidState) {
        FluidRenderer renderer = CURRENT.get();
        if (renderer == null || consumer == null) return;
        TextureAtlasSprite[] own = LegacyFluidRenders.sprites(handler, level, pos, fluidState);
        TextureAtlasSprite[] standIn = own == null ? null : standInSprites(fluidState);
        LegacyFluidQuads quads = new LegacyFluidQuads(consumer, standIn, standIn == null ? null : own);
        renderer.tesselate(level, pos, layer -> quads, blockState, fluidState);
        quads.commit();
    }

    // Old handlers looked their sprites up when the block atlas reloaded; this asks once per atlas.
    private void reloadOnce() {
        TextureAtlas atlas;
        try {
            atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(TextureAtlas.LOCATION_BLOCKS);
        } catch (RuntimeException | LinkageError notLoaded) {
            return;
        }
        if (atlas == reloadedAtlas) return;
        synchronized (this) {
            if (atlas == reloadedAtlas) return;
            LegacyFluidRenders.reload(handler, atlas);
            reloadedAtlas = atlas;
        }
    }

    // The sprites of the stand-in model Retromod gave the fluid, which the host's geometry is drawn with.
    private static TextureAtlasSprite[] standInSprites(FluidState fluidState) {
        try {
            FluidModel model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluidState);
            if (model == null) return null;
            return new TextureAtlasSprite[]{sprite(model.stillMaterial()), sprite(model.flowingMaterial()), sprite(model.overlayMaterial())};
        } catch (RuntimeException | LinkageError none) {
            return null;
        }
    }

    private static TextureAtlasSprite sprite(net.minecraft.client.resources.model.sprite.Material.Baked material) {
        return material == null ? null : material.sprite();
    }
}
