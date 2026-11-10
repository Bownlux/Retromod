// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.item;

import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.sprite.Material;

// A record on the host.
public final class ModelRenderProperties {
    private ModelRenderProperties() {}
    public boolean usesBlockLight() { throw new UnsupportedOperationException(); }
    public Material.Baked particleMaterial() { throw new UnsupportedOperationException(); }
    public ItemTransforms transforms() { throw new UnsupportedOperationException(); }
    public static ModelRenderProperties fromResolvedModel(net.minecraft.client.resources.model.ModelBaker baker,
            net.minecraft.client.resources.model.ResolvedModel model, net.minecraft.client.resources.model.sprite.TextureSlots slots) {
        throw new UnsupportedOperationException();
    }
    public void applyToLayer(ItemStackRenderState.LayerRenderState layer, net.minecraft.world.item.ItemDisplayContext context) {
        throw new UnsupportedOperationException();
    }
}
