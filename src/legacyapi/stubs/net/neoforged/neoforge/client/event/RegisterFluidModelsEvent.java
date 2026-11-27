// Compile-only stub of a NeoForge 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.client.event;

import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

public final class RegisterFluidModelsEvent extends Event implements IModBusEvent {
    private RegisterFluidModelsEvent() {}
    public void register(FluidModel.Unbaked model, Fluid fluid) { throw new UnsupportedOperationException(); }
}
