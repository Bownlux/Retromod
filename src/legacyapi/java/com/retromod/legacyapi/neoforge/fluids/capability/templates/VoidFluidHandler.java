/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.fluids.capability.templates;

import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.FluidStack;

/** A handler that accepts any amount of any fluid and keeps none of it. */
public class VoidFluidHandler implements IFluidHandler {

    public static final VoidFluidHandler INSTANCE = new VoidFluidHandler();

    public VoidFluidHandler() {}

    @Override public int getTanks() { return 1; }
    @Override public FluidStack getFluidInTank(int tank) { return FluidStack.EMPTY; }
    @Override public int getTankCapacity(int tank) { return Integer.MAX_VALUE; }
    @Override public boolean isFluidValid(int tank, FluidStack stack) { return true; }
    @Override public int fill(FluidStack resource, FluidAction action) { return resource.getAmount(); }
    @Override public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }
    @Override public FluidStack drain(int maxDrain, FluidAction action) { return FluidStack.EMPTY; }
}
