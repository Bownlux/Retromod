/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

/** Fabric API's old quad calls: {@code spriteBake}, which 26.x replaced with {@code materialBake}, and the render material calls. */
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

    /** {@code emitter.material(material)}: 26.x sets a quad's layer, glow and shading one by one, and the old material carries none. */
    public static QuadEmitter material(QuadEmitter emitter, Object material) {
        return emitter;
    }

    /** {@code quad.material(material)} on the mutable view, the same way. */
    public static MutableQuadView material(MutableQuadView quad, Object material) {
        return quad;
    }

    /** {@code emitter.fromVanilla(quad, material, cullFace)}: the quad's data and cull face; the material carries nothing. */
    public static QuadEmitter fromVanilla(QuadEmitter emitter, BakedQuad quad, Object material, Direction cullFace) {
        emitter.fromBakedQuad(quad);
        return emitter.cullFace(cullFace);
    }

    /** {@code quad.faceNormal()}, which returned a mutable vector; a copy keeps old code from changing the quad's own. */
    public static Vector3f faceNormal(QuadView quad) {
        return new Vector3f(quad.faceNormal());
    }

    // The old bake never forced translucency; that stays the default here.
    private static Material.Baked material(TextureAtlasSprite sprite) {
        return new Material.Baked(sprite, false);
    }
}
