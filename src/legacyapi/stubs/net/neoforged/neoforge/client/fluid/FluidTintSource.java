// Compile-only stub of a NeoForge 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.client.fluid;

import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.world.level.material.FluidState;

public interface FluidTintSource extends BlockTintSource {
    int color(FluidState state);
}
