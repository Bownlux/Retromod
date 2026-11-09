// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model;

import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;

public interface ResolvedModel extends ModelDebugName {
    UnbakedModel wrapped();
    ResolvedModel parent();
    default TextureSlots getTopTextureSlots() { throw new UnsupportedOperationException(); }
    default boolean getTopAmbientOcclusion() { throw new UnsupportedOperationException(); }
    default UnbakedModel.GuiLight getTopGuiLight() { throw new UnsupportedOperationException(); }
    default UnbakedGeometry getTopGeometry() { throw new UnsupportedOperationException(); }
    default QuadCollection bakeTopGeometry(TextureSlots slots, ModelBaker baker, ModelState state) { throw new UnsupportedOperationException(); }
    default Material.Baked resolveParticleMaterial(TextureSlots slots, ModelBaker baker) { throw new UnsupportedOperationException(); }
    default ItemTransforms getTopTransforms() { throw new UnsupportedOperationException(); }
    default IGeometryBakingContext getContext() { throw new UnsupportedOperationException(); }
}
