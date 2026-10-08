/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stand-in for the 1.12.2 {@code FluidRegistry} entry points a mod calls from its static
 * initializer. 1.13 replaced the fluid system, so a 1.12 fluid is not registered with the host.
 * The registry keeps the mod's {@link LegacyFluid} definitions by name, so the common
 * "register it, or reuse the one another mod registered" pattern hands back a real definition.
 */
public final class LegacyFluidRegistry {

    private static final java.util.Map<String, LegacyFluid> FLUIDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The vanilla fluids 1.12 pre-registered, so {@code getFluid("water")} still finds one. */
    public static final LegacyFluid WATER = vanilla("water", 1000, 1000, 300);
    public static final LegacyFluid LAVA = vanilla("lava", 3000, 6000, 1300);

    private LegacyFluidRegistry() {}

    public static void enableUniversalBucket() {
    }

    public static boolean isUniversalBucketEnabled() {
        return false;
    }

    public static boolean registerFluid(Object fluid) {
        LegacyForgeEvents.note("a 1.12 fluid", fluid);
        if (!(fluid instanceof LegacyFluid legacy)) return false;
        return FLUIDS.putIfAbsent((String) legacy.getName(), legacy) == null;
    }

    public static boolean addBucketForFluid(Object fluid) {
        return false;
    }

    /** Takes a fluid name or a fluid, like the two 1.12 overloads. */
    public static boolean isFluidRegistered(Object fluid) {
        if (fluid instanceof LegacyFluid legacy) return FLUIDS.get((String) legacy.getName()) == legacy;
        return fluid != null && FLUIDS.containsKey(fluid.toString());
    }

    public static LegacyFluid getFluid(Object fluidName) {
        return fluidName == null ? null : FLUIDS.get(fluidName.toString());
    }

    public static Object getFluidName(Object fluid) {
        if (fluid instanceof LegacyFluidStack stack) fluid = stack.getFluid();
        return fluid instanceof LegacyFluid legacy ? legacy.getName() : null;
    }

    private static LegacyFluid vanilla(String name, int density, int viscosity, int temperature) {
        LegacyFluid fluid = new LegacyFluid(name, null, null).setDensity(density)
                .setViscosity(viscosity).setTemperature(temperature);
        FLUIDS.put(name, fluid);
        return fluid;
    }

    public static LegacyFluidStack getFluidStack(Object fluidName, int amount) {
        LegacyFluid fluid = getFluid(fluidName);
        return fluid == null ? null : new LegacyFluidStack(fluid, amount);
    }
}
