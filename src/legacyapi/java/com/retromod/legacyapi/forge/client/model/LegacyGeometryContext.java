/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.mojang.math.Transformation;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.RenderTypeGroup;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
import net.minecraftforge.client.model.geometry.StandaloneGeometryBakingContext;

/** The old baking context of one model: Forge 26.2's context, plus the model's texture slots and name. */
final class LegacyGeometryContext implements LegacyGeometryBakingContext {

    // Null when Minecraft baked the geometry without Forge's context.
    private final IGeometryBakingContext host;
    private final TextureSlots slots;
    private final String name;

    LegacyGeometryContext(IGeometryBakingContext host, TextureSlots slots, String name) {
        this.host = host;
        this.slots = slots;
        this.name = name;
    }

    @Override
    public String getModelName() {
        return name;
    }

    @Override
    public boolean hasMaterial(String name) {
        return material(name) != null;
    }

    /** The slot's texture on the block atlas, or the missing texture, as Forge 1.20.1 answered. */
    @Override
    public SpriteId getMaterial(String name) {
        Material material = material(name);
        Identifier texture = material == null ? MissingTextureAtlasSprite.getLocation() : material.sprite();
        return new SpriteId(TextureAtlas.LOCATION_BLOCKS, texture);
    }

    // Old code wrote a slot with or without the '#' of a reference.
    private Material material(String slot) {
        if (slots == null || slot == null) return null;
        return slots.getMaterial(slot.startsWith("#") ? slot.substring(1) : slot);
    }

    @Override
    public boolean isGui3d() {
        return host == null || host.isGui3d();
    }

    @Override
    public boolean useBlockLight() {
        return host == null || host.useBlockLight();
    }

    @Override
    public boolean useAmbientOcclusion() {
        return host == null || host.useAmbientOcclusion();
    }

    @Override
    public ItemTransforms getTransforms() {
        return host == null ? ItemTransforms.NO_TRANSFORMS : host.getTransforms();
    }

    @Override
    public Transformation getRootTransform() {
        return host == null ? Transformation.IDENTITY : host.getRootTransform();
    }

    @Override
    public Identifier getRenderTypeHint() {
        return host == null ? null : host.getRenderTypeHint();
    }

    @Override
    public Identifier getRenderTypeFastHint() {
        return host == null ? null : host.getRenderTypeFastHint();
    }

    @Override
    public boolean isComponentVisible(String component, boolean fallback) {
        return host == null ? fallback : host.isComponentVisible(component, fallback);
    }

    @Override
    public RenderTypeGroup getRenderType(Identifier name) {
        return host == null ? namedRenderType(name) : host.getRenderType(name);
    }

    /** The chunk layer the model's render_type names, which Forge 1.20.1 drew the whole model in; null for none. */
    ChunkSectionLayer hintLayer() {
        Identifier hint = getRenderTypeHint();
        if (hint == null) return null;
        RenderTypeGroup group = getRenderType(hint);
        return group == null ? null : group.block();
    }

    /** A render type group by name, through Forge's own lookup. */
    static RenderTypeGroup namedRenderType(Identifier name) {
        return name == null ? RenderTypeGroup.EMPTY : StandaloneGeometryBakingContext.INSTANCE.getRenderType(name);
    }
}
