/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.block;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.FluidState;

/** A fluid's textures and tint as the running game's fluid model holds them, for old fluid reads on 26.x. */
public final class LegacyFluidModels {

    /** The still, flowing and overlay slots of {@link #sprites}. */
    public static final int STILL = 0, FLOWING = 1, OVERLAY = 2;
    /** The tint an untinted fluid had in the old reads. */
    public static final int WHITE = 0xFFFFFFFF;

    private LegacyFluidModels() {}

    /** The still and flowing sprites of the state's model, and its overlay when it has one; both are null before models load. */
    public static TextureAtlasSprite[] sprites(FluidState state) {
        FluidModel model = model(state);
        if (model == null) return new TextureAtlasSprite[2];
        TextureAtlasSprite overlay = sprite(model.overlayMaterial());
        TextureAtlasSprite still = sprite(model.stillMaterial()), flowing = sprite(model.flowingMaterial());
        return overlay == null ? new TextureAtlasSprite[]{still, flowing} : new TextureAtlasSprite[]{still, flowing, overlay};
    }

    /** The texture id in one slot of the state's model, or null when the model has none. */
    public static Identifier texture(FluidState state, int slot) {
        TextureAtlasSprite[] sprites = sprites(state);
        TextureAtlasSprite sprite = slot < sprites.length ? sprites[slot] : null;
        return sprite == null ? null : sprite.contents().name();
    }

    /** The model's tint at a position, or its plain tint when the level is unknown; white without a model or tint. */
    public static int tint(FluidState state, BlockAndTintGetter level, BlockPos pos) {
        FluidModel model = model(state);
        BlockTintSource tint = model == null ? null : model.tintSource();
        if (tint == null) return WHITE;
        // The tint sources read the block form of the fluid, which is what the world holds at that spot.
        if (level != null && pos != null) return tint.colorInWorld(state.createLegacyBlock(), level, pos);
        return tint.color(state.createLegacyBlock());
    }

    /** A fluid model that draws {@code still}, {@code flowing} and {@code overlay} (null for none) with {@code tint}. */
    public static FluidModel.Unbaked unbaked(Identifier still, Identifier flowing, Identifier overlay, BlockTintSource tint) {
        return new FluidModel.Unbaked(new Material(still), new Material(flowing), overlay == null ? null : new Material(overlay), tint);
    }

    /** The block atlas sprite of a texture id, or null on a server or before the atlas loads. */
    public static TextureAtlasSprite blockSprite(Identifier texture) {
        if (texture == null) return null;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft == null ? null : minecraft.getAtlasManager().get(new SpriteId(TextureAtlas.LOCATION_BLOCKS, texture));
        } catch (RuntimeException | LinkageError noAtlas) {
            return null;
        }
    }

    // The host's model for the state; null on a server or before the first resource load.
    private static FluidModel model(FluidState state) {
        if (state == null) return null;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.getModelManager() == null) return null;
            return minecraft.getModelManager().getFluidStateModelSet().get(state);
        } catch (RuntimeException | LinkageError noModels) {
            return null;
        }
    }

    private static TextureAtlasSprite sprite(Material.Baked material) {
        return material == null ? null : material.sprite();
    }
}
