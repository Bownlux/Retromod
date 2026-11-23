/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;

/** Fabric API's old {@code spriteBake}, which 26.x Fabric API replaced with {@code materialBake}. */
public final class LegacyQuadEmitters {

    private LegacyQuadEmitters() {}

    /** {@code emitter.spriteBake(sprite, flags)}. */
    public static QuadEmitter spriteBake(QuadEmitter emitter, TextureAtlasSprite sprite, int bakeFlags) {
        return emitter.materialBake(material(sprite), bakeFlags);
    }

    /** {@code emitter.spriteBake(spriteIndex, sprite, flags)}; quads have had one sprite for years, so the index is ignored. */
    public static QuadEmitter spriteBake(QuadEmitter emitter, int spriteIndex, TextureAtlasSprite sprite, int bakeFlags) {
        return spriteBake(emitter, sprite, bakeFlags);
    }

    /** {@code quad.spriteBake(sprite, flags)} on the mutable view. */
    public static MutableQuadView spriteBake(MutableQuadView quad, TextureAtlasSprite sprite, int bakeFlags) {
        return quad.materialBake(material(sprite), bakeFlags);
    }

    /** {@code quad.spriteBake(spriteIndex, sprite, flags)}. */
    public static MutableQuadView spriteBake(MutableQuadView quad, int spriteIndex, TextureAtlasSprite sprite, int bakeFlags) {
        return spriteBake(quad, sprite, bakeFlags);
    }

    // The old bake never forced translucency; that stays the default here.
    private static Material.Baked material(TextureAtlasSprite sprite) {
        return new Material.Baked(sprite, false);
    }
}
