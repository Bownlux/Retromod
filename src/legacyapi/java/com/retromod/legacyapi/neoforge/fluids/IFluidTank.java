/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.fluids;

import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.FluidStack;

/** A single tank of fluid. */
public interface IFluidTank {

    FluidStack getFluid();

    int getFluidAmount();

    int getCapacity();

    boolean isFluidValid(FluidStack stack);

    int fill(FluidStack resource, IFluidHandler.FluidAction action);

    FluidStack drain(int maxDrain, IFluidHandler.FluidAction action);

    FluidStack drain(FluidStack resource, IFluidHandler.FluidAction action);
}
