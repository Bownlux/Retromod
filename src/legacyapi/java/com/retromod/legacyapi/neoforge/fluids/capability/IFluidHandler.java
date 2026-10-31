/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.fluids.capability;

import com.retromod.legacyapi.neoforge.bridge.FluidHandlerResource;
import com.retromod.legacyapi.neoforge.bridge.ResourceFluidHandler;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/** NeoForge's tank-based fluid handler, which 26.3 removed, as old mods call and implement it. */
public interface IFluidHandler {

    /** The old handler for a current one; a current handler that wraps an old one gives that one back. */
    static IFluidHandler of(ResourceHandler<FluidResource> handler) {
        if (handler instanceof FluidHandlerResource adapter) return adapter.handler();
        return new ResourceFluidHandler(handler);
    }

    /** Whether a fill or drain happens or only reports what it would do. */
    enum FluidAction {
        EXECUTE, SIMULATE;

        public boolean execute() {
            return this == EXECUTE;
        }

        public boolean simulate() {
            return this == SIMULATE;
        }
    }

    int getTanks();

    FluidStack getFluidInTank(int tank);

    int getTankCapacity(int tank);

    boolean isFluidValid(int tank, FluidStack stack);

    int fill(FluidStack resource, FluidAction action);

    FluidStack drain(FluidStack resource, FluidAction action);

    FluidStack drain(int maxDrain, FluidAction action);
}
