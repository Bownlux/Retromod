// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.item;

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.function.Supplier;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.sprite.Material;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

// Only the members 26.2 and 26.3 share: the layer's quad setter differs between them.
public class ItemStackRenderState {
    public ItemStackRenderState() {}

    public boolean isEmpty() { throw new UnsupportedOperationException(); }
    public void clear() { throw new UnsupportedOperationException(); }
    public boolean usesBlockLight() { throw new UnsupportedOperationException(); }
    public Material.Baked pickParticleMaterial(net.minecraft.util.RandomSource random) { throw new UnsupportedOperationException(); }
    public void submit(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector submitNodeCollector,
            int lightCoords, int overlayCoords, int outlineColor) {
        throw new UnsupportedOperationException();
    }
    public LayerRenderState newLayer() { throw new UnsupportedOperationException(); }
    public void appendModelIdentityElement(Object element) { throw new UnsupportedOperationException(); }
    public void setAnimated() { throw new UnsupportedOperationException(); }

    public enum FoilType {
        NONE, STANDARD, SPECIAL
    }

    public class LayerRenderState {
        public void setUsesBlockLight(boolean usesBlockLight) { throw new UnsupportedOperationException(); }
        public void setExtents(Supplier<Vector3fc[]> extents) { throw new UnsupportedOperationException(); }
        public void setParticleMaterial(Material.Baked particleMaterial) { throw new UnsupportedOperationException(); }
        public void setItemTransform(ItemTransform transform) { throw new UnsupportedOperationException(); }
        public void setLocalTransform(Matrix4fc transform) { throw new UnsupportedOperationException(); }
        public void setFoilType(FoilType foilType) { throw new UnsupportedOperationException(); }
        public IntList tintLayers() { throw new UnsupportedOperationException(); }
        public <T> void setupSpecialModel(net.minecraft.client.renderer.special.SpecialModelRenderer<T> renderer, T argument) {
            throw new UnsupportedOperationException();
        }
    }
}
