// Compile-only stub of a Fabric API host type on 26.x for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.renderer.v1.mesh;

import net.minecraft.client.resources.model.sprite.Material;

public interface QuadEmitter extends MutableQuadView {
    @Override
    QuadEmitter materialBake(Material.Baked material, int bakeFlags);
    @Override
    QuadEmitter cullFace(net.minecraft.core.Direction face);
    @Override
    QuadEmitter fromBakedQuad(net.minecraft.client.resources.model.geometry.BakedQuad quad);
    @Override
    QuadEmitter color(int vertexIndex, int color);
    @Override
    QuadEmitter uv(int vertexIndex, float u, float v);
    void pushTransform(QuadTransform transform);
    void popTransform();
    QuadEmitter emit();
}
