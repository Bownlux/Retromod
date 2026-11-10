// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model;

import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;

// A record on the host.
public final class SimpleModelWrapper implements BlockStateModelPart {
    public SimpleModelWrapper(QuadCollection quads, boolean useAmbientOcclusion, Material.Baked particleMaterial) { throw new UnsupportedOperationException(); }
    public QuadCollection quads() { throw new UnsupportedOperationException(); }
    public List<BakedQuad> getQuads(Direction side) { throw new UnsupportedOperationException(); }
    public boolean useAmbientOcclusion() { throw new UnsupportedOperationException(); }
    public Material.Baked particleMaterial() { throw new UnsupportedOperationException(); }
    public int materialFlags() { throw new UnsupportedOperationException(); }
}
