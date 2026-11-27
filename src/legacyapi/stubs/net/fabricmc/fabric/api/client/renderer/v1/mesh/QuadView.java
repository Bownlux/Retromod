// Compile-only stub of a Fabric API host type on 26.x for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.renderer.v1.mesh;

import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;

public interface QuadView {
    org.joml.Vector3fc faceNormal();
    float x(int vertexIndex);
    float y(int vertexIndex);
    float z(int vertexIndex);
    float u(int vertexIndex);
    float v(int vertexIndex);
    int color(int vertexIndex);
    int lightmap(int vertexIndex);
    Direction cullFace();
    Direction nominalFace();
    Direction lightFace();
    ChunkSectionLayer chunkLayer();
    boolean emissive();
    TriState ambientOcclusion();
    ShadeMode shadeMode();
    int tintIndex();
    int tag();
    BakedQuad toBakedQuad(TextureAtlasSprite sprite);
}
