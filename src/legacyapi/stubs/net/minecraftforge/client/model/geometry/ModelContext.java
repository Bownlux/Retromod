// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.model.geometry;

import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraftforge.client.model.ForgeBlockModelData;

// A record on the host.
public final class ModelContext implements IGeometryBakingContext {
    public ModelContext(ResolvedModel self) {}
    public ModelContext(ResolvedModel self, ResolvedModel parent, ForgeBlockModelData data, boolean gui3d) {}
    public boolean isGui3d() { throw new UnsupportedOperationException(); }
    public boolean useBlockLight() { throw new UnsupportedOperationException(); }
    public boolean useAmbientOcclusion() { throw new UnsupportedOperationException(); }
    public net.minecraft.client.resources.model.cuboid.ItemTransforms getTransforms() { throw new UnsupportedOperationException(); }
    public com.mojang.math.Transformation getRootTransform() { throw new UnsupportedOperationException(); }
    public net.minecraft.resources.Identifier getRenderTypeHint() { throw new UnsupportedOperationException(); }
    public boolean isComponentVisible(String component, boolean fallback) { throw new UnsupportedOperationException(); }
}
