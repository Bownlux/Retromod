/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import java.util.List;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** An old BakedModel that reads Minecraft 26.2's item model, as Forge 1.20.1's getModels() handed out item models. */
public final class LegacyItemModelView implements BakedModel {

    private final ItemModel host;

    public LegacyItemModelView(ItemModel host) {
        this.host = host;
    }

    /** The host model this view reads. */
    public ItemModel host() {
        return host;
    }

    /** Whether the host model keeps quads Retromod can read; a select or special item model does not. */
    public boolean readable() {
        return LegacyItemQuads.readable(host);
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        return LegacyItemQuads.read(host, side);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        ModelRenderProperties properties = LegacyItemQuads.properties(host);
        return properties == null || properties.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        ModelRenderProperties properties = LegacyItemQuads.properties(host);
        Material.Baked particle = properties == null ? null : properties.particleMaterial();
        return particle == null ? null : particle.sprite();
    }

    @Override
    public ItemTransforms getTransforms() {
        ModelRenderProperties properties = LegacyItemQuads.properties(host);
        return properties == null || properties.transforms() == null ? ItemTransforms.NO_TRANSFORMS : properties.transforms();
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    @Override
    public String toString() {
        return "LegacyItemModelView[" + host + "]";
    }
}
