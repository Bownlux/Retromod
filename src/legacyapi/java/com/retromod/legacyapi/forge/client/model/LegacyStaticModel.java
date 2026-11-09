/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import java.util.List;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** An old BakedModel with fixed quads, as Minecraft 1.20.1's SimpleBakedModel was, for a nested model Retromod baked. */
final class LegacyStaticModel implements BakedModel {

    private final LegacyNestedModels.Baked baked;

    LegacyStaticModel(LegacyNestedModels.Baked baked) {
        this.baked = baked;
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        return baked.quads().getQuads(side);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return baked.ambientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return baked.guiLight() == null || baked.guiLight().lightLikeBlock();
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return baked.particle() == null ? null : baked.particle().sprite();
    }

    @Override
    public ItemTransforms getTransforms() {
        return baked.transforms() == null ? ItemTransforms.NO_TRANSFORMS : baked.transforms();
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    @Override
    public String toString() {
        return "LegacyStaticModel[" + baked.quads().getAll().size() + " quads]";
    }
}
