/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Minecraft 1.21.1's BlockModelShaper, which 26.1 removed: each block state's model, read from Minecraft's block state models. */
public class LegacyBlockModelShaper {

    static final LegacyBlockModelShaper INSTANCE = new LegacyBlockModelShaper();

    public LegacyBlockModelShaper() {}

    /** The state's model; an empty one while Minecraft has no models yet, as old code never expected null. */
    public BlockStateModel getBlockModel(BlockState state) {
        BlockStateModelSet models = models();
        BlockStateModel model = models == null ? null : models.get(state);
        return model != null ? model : LegacyBlockRenderDispatcher.EMPTY;
    }

    public TextureAtlasSprite getParticleIcon(BlockState state) {
        BlockStateModelSet models = models();
        Material.Baked particle = models == null ? null : models.getParticleMaterial(state);
        return particle == null ? null : particle.sprite();
    }

    /** Forge 1.20.1's per-position particle texture, which the state's particle answers. */
    public TextureAtlasSprite getTexture(BlockState state, Level level, BlockPos pos) {
        return getParticleIcon(state);
    }

    public ModelManager getModelManager() {
        return Minecraft.getInstance().getModelManager();
    }

    /** The block state models of the running game, or null before its models load. */
    static BlockStateModelSet models() {
        Minecraft minecraft = Minecraft.getInstance();
        ModelManager manager = minecraft == null ? null : minecraft.getModelManager();
        return manager == null ? null : manager.getBlockStateModelSet();
    }
}
