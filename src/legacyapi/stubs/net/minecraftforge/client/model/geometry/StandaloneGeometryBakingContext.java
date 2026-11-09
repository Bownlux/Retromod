// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.model.geometry;

public class StandaloneGeometryBakingContext implements IGeometryBakingContext {
    public static final StandaloneGeometryBakingContext INSTANCE = null;
    private StandaloneGeometryBakingContext() {}
    public boolean isGui3d() { throw new UnsupportedOperationException(); }
    public boolean useBlockLight() { throw new UnsupportedOperationException(); }
    public boolean useAmbientOcclusion() { throw new UnsupportedOperationException(); }
    public net.minecraft.client.resources.model.cuboid.ItemTransforms getTransforms() { throw new UnsupportedOperationException(); }
    public com.mojang.math.Transformation getRootTransform() { throw new UnsupportedOperationException(); }
    public net.minecraft.resources.Identifier getRenderTypeHint() { throw new UnsupportedOperationException(); }
    public boolean isComponentVisible(String component, boolean fallback) { throw new UnsupportedOperationException(); }
}
