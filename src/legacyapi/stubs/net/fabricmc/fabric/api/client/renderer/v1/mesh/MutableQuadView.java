// Compile-only stub of a Fabric API host type on 26.x for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.renderer.v1.mesh;

import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;

public interface MutableQuadView extends QuadView {
    MutableQuadView materialBake(Material.Baked material, int bakeFlags);
    MutableQuadView pos(int vertexIndex, float x, float y, float z);
    MutableQuadView color(int vertexIndex, int color);
    default MutableQuadView color(int c0, int c1, int c2, int c3) { throw new UnsupportedOperationException(); }
    MutableQuadView uv(int vertexIndex, float u, float v);
    MutableQuadView cullFace(Direction face);
    MutableQuadView nominalFace(Direction face);
    MutableQuadView chunkLayer(ChunkSectionLayer layer);
    MutableQuadView emissive(boolean emissive);
    MutableQuadView ambientOcclusion(TriState ao);
    MutableQuadView shadeMode(ShadeMode mode);
    MutableQuadView tintIndex(int tintIndex);
    MutableQuadView copyFrom(QuadView quad);
    MutableQuadView fromBakedQuad(BakedQuad quad);
    // 26.1 and 26.2 only; 26.3 replaced it with shadeDirectionOverride.
    MutableQuadView diffuseShade(boolean shade);
    // 26.3 and newer.
    MutableQuadView shadeDirectionOverride(Direction shadeDirection);
}
