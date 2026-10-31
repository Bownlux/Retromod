/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.fluids.capability.templates;

import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.FluidStack;

/** A handler with no tanks. */
public class EmptyFluidHandler implements IFluidHandler {

    public static final EmptyFluidHandler INSTANCE = new EmptyFluidHandler();

    protected EmptyFluidHandler() {}

    @Override public int getTanks() { return 1; }
    @Override public FluidStack getFluidInTank(int tank) { return FluidStack.EMPTY; }
    @Override public int getTankCapacity(int tank) { return 0; }
    @Override public boolean isFluidValid(int tank, FluidStack stack) { return true; }
    @Override public int fill(FluidStack resource, FluidAction action) { return 0; }
    @Override public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }
    @Override public FluidStack drain(int maxDrain, FluidAction action) { return FluidStack.EMPTY; }
}
