// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.model.geometry;

import com.mojang.math.Transformation;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.RenderTypeGroup;

public interface IGeometryBakingContext {
    boolean isGui3d();
    boolean useBlockLight();
    boolean useAmbientOcclusion();
    ItemTransforms getTransforms();
    Transformation getRootTransform();
    Identifier getRenderTypeHint();
    default Identifier getRenderTypeFastHint() { throw new UnsupportedOperationException(); }
    boolean isComponentVisible(String component, boolean fallback);
    default RenderTypeGroup getRenderType(Identifier name) { throw new UnsupportedOperationException(); }
}
