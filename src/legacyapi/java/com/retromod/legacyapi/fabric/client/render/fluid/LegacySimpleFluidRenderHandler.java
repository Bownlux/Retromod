/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.render.fluid;

import com.retromod.legacyapi.minecraft.client.renderer.block.LegacyFluidModels;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.FluidState;

/** Fabric API's {@code SimpleFluidRenderHandler} of 1.20.1 and 1.21.1, which Fabric API for 26.1 removed, as a handler whose textures make a fluid model. */
public class LegacySimpleFluidRenderHandler implements FluidRenderHandler {

    public static final Identifier WATER_STILL = Identifier.withDefaultNamespace("block/water_still");
    public static final Identifier WATER_FLOWING = Identifier.withDefaultNamespace("block/water_flow");
    public static final Identifier WATER_OVERLAY = Identifier.withDefaultNamespace("block/water_overlay");
    public static final Identifier LAVA_STILL = Identifier.withDefaultNamespace("block/lava_still");
    public static final Identifier LAVA_FLOWING = Identifier.withDefaultNamespace("block/lava_flow");

    protected final Identifier stillTexture;
    protected final Identifier flowingTexture;
    protected final Identifier overlayTexture;
    protected final TextureAtlasSprite[] sprites;
    protected final int tint;

    public LegacySimpleFluidRenderHandler(Identifier stillTexture, Identifier flowingTexture, Identifier overlayTexture, int tint) {
        this.stillTexture = stillTexture;
        this.flowingTexture = flowingTexture;
        this.overlayTexture = overlayTexture;
        this.sprites = new TextureAtlasSprite[overlayTexture == null ? 2 : 3];
        this.tint = tint;
    }

    public LegacySimpleFluidRenderHandler(Identifier stillTexture, Identifier flowingTexture, Identifier overlayTexture) {
        this(stillTexture, flowingTexture, overlayTexture, -1);
    }

    public LegacySimpleFluidRenderHandler(Identifier stillTexture, Identifier flowingTexture, int tint) {
        this(stillTexture, flowingTexture, null, tint);
    }

    public LegacySimpleFluidRenderHandler(Identifier stillTexture, Identifier flowingTexture) {
        this(stillTexture, flowingTexture, null, -1);
    }

    public static LegacySimpleFluidRenderHandler coloredWater(int tint) {
        return new LegacySimpleFluidRenderHandler(WATER_STILL, WATER_FLOWING, WATER_OVERLAY, tint);
    }

    /** The still, flowing and overlay sprites from the block atlas; each is null until the atlas has loaded. */
    public TextureAtlasSprite[] getFluidSprites(BlockAndTintGetter view, BlockPos pos, FluidState state) {
        sprites[0] = LegacyFluidModels.blockSprite(stillTexture);
        sprites[1] = LegacyFluidModels.blockSprite(flowingTexture);
        if (overlayTexture != null) sprites[2] = LegacyFluidModels.blockSprite(overlayTexture);
        return sprites;
    }

    /** The atlas is read when the sprites are asked for, so a reload needs nothing here. */
    public void reloadTextures(TextureAtlas atlas) {}

    public int getFluidColor(BlockAndTintGetter view, BlockPos pos, FluidState state) {
        return tint;
    }
}
