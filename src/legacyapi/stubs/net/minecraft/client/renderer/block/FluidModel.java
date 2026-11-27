// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.block;

import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.resources.model.sprite.Material;

public final class FluidModel {
    private FluidModel() {}
    public Material.Baked stillMaterial() { throw new UnsupportedOperationException(); }
    public Material.Baked flowingMaterial() { throw new UnsupportedOperationException(); }
    public Material.Baked overlayMaterial() { throw new UnsupportedOperationException(); }
    public BlockTintSource tintSource() { throw new UnsupportedOperationException(); }

    public static final class Unbaked {
        public Unbaked(Material still, Material flowing, Material overlay, BlockTintSource tint) {}
    }
}
