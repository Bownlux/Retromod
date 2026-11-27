// Compile-only stub of a Fabric API 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.render.fluid.v1;

import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

public final class FluidRenderingRegistry {
    private FluidRenderingRegistry() {}
    public static void register(Fluid still, Fluid flowing, FluidModel.Unbaked model) { throw new UnsupportedOperationException(); }
    public static void register(Fluid fluid, FluidModel.Unbaked model) { throw new UnsupportedOperationException(); }
    public static void setBlockTransparency(Block block, boolean transparent) { throw new UnsupportedOperationException(); }
    public static boolean isBlockTransparent(Block block) { throw new UnsupportedOperationException(); }
}
