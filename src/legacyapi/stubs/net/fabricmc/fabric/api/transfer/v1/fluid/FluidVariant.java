// Compile-only stub of a Fabric API 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.transfer.v1.fluid;

import net.fabricmc.fabric.api.transfer.v1.storage.TransferVariant;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.level.material.Fluid;

public interface FluidVariant extends TransferVariant<Fluid> {
    static FluidVariant of(Fluid fluid) { throw new UnsupportedOperationException(); }
    static FluidVariant of(Fluid fluid, DataComponentPatch components) { throw new UnsupportedOperationException(); }
}
