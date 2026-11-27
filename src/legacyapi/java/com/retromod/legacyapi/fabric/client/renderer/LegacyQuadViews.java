/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;

/** Old quad, material, sprite finder and model helper calls that 26.x's renderer API dropped or renamed. */
public final class LegacyQuadViews {

    /** Old {@code QuadView.VANILLA_VERTEX_STRIDE}: ints per vertex in 1.20 and 1.21's block vertex data. */
    public static final int VANILLA_VERTEX_STRIDE = 8;
    /** Old {@code QuadView.VANILLA_QUAD_STRIDE}. */
    public static final int VANILLA_QUAD_STRIDE = VANILLA_VERTEX_STRIDE * 4;

    private LegacyQuadViews() {}

    /** {@code emitter.material(material)}: the quad takes on the material's properties. */
    public static QuadEmitter material(QuadEmitter emitter, LegacyRenderMaterial material) {
        LegacyRenderMaterials.apply(emitter, material);
        return emitter;
    }

    /** {@code quad.material(material)} on the mutable view. */
    public static MutableQuadView material(MutableQuadView quad, LegacyRenderMaterial material) {
        LegacyRenderMaterials.apply(quad, material);
        return quad;
    }

    /** {@code quad.material()}: the material the quad's properties describe. */
    public static LegacyRenderMaterial material(QuadView quad) {
        return LegacyRenderMaterials.read(quad);
    }

    /** {@code emitter.fromVanilla(quad, material, cullFace)}. */
    public static QuadEmitter fromVanilla(QuadEmitter emitter, BakedQuad quad, LegacyRenderMaterial material, Direction cullFace) {
        emitter.fromBakedQuad(quad);
        emitter.cullFace(cullFace);
        if (material != null) LegacyRenderMaterials.apply(emitter, material);
        return emitter;
    }

    /** {@code view.fromVanilla(quad, material, cullFace)} as 1.16 to 1.19 declared it, returning the mutable view. */
    public static MutableQuadView fromVanilla(MutableQuadView view, BakedQuad quad, LegacyRenderMaterial material, Direction cullFace) {
        view.fromBakedQuad(quad);
        view.cullFace(cullFace);
        if (material != null) LegacyRenderMaterials.apply(view, material);
        return view;
    }

    /** {@code emitter.spriteColor(vertex, spriteIndex, color)}: quads have one sprite, so the index is ignored. */
    public static QuadEmitter spriteColor(QuadEmitter emitter, int vertexIndex, int spriteIndex, int color) {
        return emitter.color(vertexIndex, color);
    }

    /** {@code emitter.spriteColor(spriteIndex, c0, c1, c2, c3)}. */
    public static QuadEmitter spriteColor(QuadEmitter emitter, int spriteIndex, int c0, int c1, int c2, int c3) {
        emitter.color(c0, c1, c2, c3);
        return emitter;
    }

    /** {@code emitter.sprite(vertex, spriteIndex, u, v)}. */
    public static QuadEmitter sprite(QuadEmitter emitter, int vertexIndex, int spriteIndex, float u, float v) {
        return emitter.uv(vertexIndex, u, v);
    }

    /** {@code quad.spriteColor(vertex, spriteIndex, color)} on the mutable view. */
    public static MutableQuadView spriteColor(MutableQuadView quad, int vertexIndex, int spriteIndex, int color) {
        return quad.color(vertexIndex, color);
    }

    /** {@code quad.sprite(vertex, spriteIndex, u, v)} on the mutable view. */
    public static MutableQuadView sprite(MutableQuadView quad, int vertexIndex, int spriteIndex, float u, float v) {
        return quad.uv(vertexIndex, u, v);
    }

    /** {@code quad.spriteColor(vertex, spriteIndex)}. */
    public static int spriteColor(QuadView quad, int vertexIndex, int spriteIndex) {
        return quad.color(vertexIndex);
    }

    /** {@code quad.spriteU(vertex, spriteIndex)}. */
    public static float spriteU(QuadView quad, int vertexIndex, int spriteIndex) {
        return quad.u(vertexIndex);
    }

    /** {@code quad.spriteV(vertex, spriteIndex)}. */
    public static float spriteV(QuadView quad, int vertexIndex, int spriteIndex) {
        return quad.v(vertexIndex);
    }

    /** {@code quad.copyTo(target)}. */
    public static void copyTo(QuadView quad, MutableQuadView target) {
        target.copyFrom(quad);
    }

    /** {@code quad.toBakedQuad(spriteIndex, sprite, isItem)}. */
    public static BakedQuad toBakedQuad(QuadView quad, int spriteIndex, TextureAtlasSprite sprite, boolean isItem) {
        return quad.toBakedQuad(sprite);
    }

    /** {@code SpriteFinder.get(atlas)}: the finder Fabric API keeps on every atlas. */
    public static SpriteFinder spriteFinder(TextureAtlas atlas) {
        return ((FabricTextureAtlas) (Object) atlas).spriteFinder();
    }

    /** {@code finder.find(quad, textureIndex)}. */
    public static TextureAtlasSprite find(SpriteFinder finder, QuadView quad, int textureIndex) {
        return finder.find(quad);
    }

    /** {@code ModelHelper.toQuadLists(mesh)}: the mesh's quads by cull face, sides first and unculled last, on the block atlas. */
    @SuppressWarnings("unchecked")
    public static List<BakedQuad>[] toQuadLists(Mesh mesh) {
        List<BakedQuad>[] lists = new List[7];
        for (int i = 0; i < lists.length; i++) lists[i] = new ArrayList<>();
        if (mesh == null) return lists;
        SpriteFinder finder = spriteFinder(Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(TextureAtlas.LOCATION_BLOCKS));
        mesh.forEach(quad -> {
            Direction cull = quad.cullFace();
            lists[cull == null ? 6 : cull.ordinal()].add(quad.toBakedQuad(finder.find(quad)));
        });
        return lists;
    }
}
