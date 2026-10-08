/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stand-in for the 1.12.2 {@code FluidStack}: a {@link LegacyFluid} and an amount in millibuckets.
 * Mods read the public {@code amount} field directly, so it stays a field. The 1.12 NBT tag is
 * kept as an opaque object and never written to a save.
 */
public class LegacyFluidStack {

    public int amount;
    public Object tag;
    private final LegacyFluid fluid;

    public LegacyFluidStack(Object fluid, int amount) {
        this.fluid = fluid instanceof LegacyFluid legacy ? legacy
                : fluid instanceof LegacyFluidStack stack ? stack.fluid : null;
        this.amount = amount;
    }

    public LegacyFluidStack(Object fluid, int amount, Object tag) {
        this(fluid, amount);
        this.tag = tag;
    }

    public LegacyFluid getFluid() {
        return fluid;
    }

    public Object getUnlocalizedName() {
        return fluid == null ? "" : fluid.getUnlocalizedName();
    }

    public LegacyFluidStack copy() {
        return new LegacyFluidStack(fluid, amount, tag);
    }

    public boolean isFluidEqual(Object other) {
        LegacyFluid otherFluid = other instanceof LegacyFluidStack stack ? stack.fluid : null;
        return fluid != null && fluid == otherFluid;
    }

    public boolean containsFluid(Object other) {
        return isFluidEqual(other) && amount >= ((LegacyFluidStack) other).amount;
    }

    public boolean isFluidStackIdentical(Object other) {
        return isFluidEqual(other) && amount == ((LegacyFluidStack) other).amount;
    }
}
