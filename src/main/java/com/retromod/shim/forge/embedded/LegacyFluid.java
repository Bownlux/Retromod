/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stand-in for the 1.12.2 {@code net.minecraftforge.fluids.Fluid} definition object. 1.13
 * replaced it with flowing fluids and, later, fluid types, which a mod's static initializer
 * cannot build from a name and two textures. This keeps the definition and its builder values so
 * the mod's fluid fields, fluid blocks and fluid stacks link. The fluid is never registered with
 * the host, so it does not flow, fill buckets, or show in fluid tanks.
 *
 * <p>Parameters and reference results other than the stand-ins are {@code Object}, including
 * {@code String} ones: Retromod erases each call site to that shape and casts the result back,
 * because the 1.12 types ({@code ResourceLocation}, {@code Block}, {@code EnumRarity}) are not
 * visible to this compiled class.
 */
public class LegacyFluid {

    protected final String fluidName;
    protected final Object still;
    protected final Object flowing;
    protected Object overlay;
    protected String unlocalizedName;
    protected int luminosity;
    protected int density = 1000;
    protected int temperature = 300;
    protected int viscosity = 1000;
    protected boolean isGaseous;
    protected int color = 0xFFFFFFFF;
    protected Object rarity;
    protected Object fillSound;
    protected Object emptySound;
    protected Object block;

    public LegacyFluid(Object fluidName, Object still, Object flowing) {
        this.fluidName = fluidName == null ? "" : fluidName.toString().toLowerCase(java.util.Locale.ROOT);
        this.unlocalizedName = this.fluidName;
        this.still = still;
        this.flowing = flowing;
    }

    public LegacyFluid(Object fluidName, Object still, Object flowing, Object overlay) {
        this(fluidName, still, flowing);
        this.overlay = overlay;
    }

    public LegacyFluid(Object fluidName, Object still, Object flowing, int color) {
        this(fluidName, still, flowing);
        this.color = color;
    }

    public LegacyFluid(Object fluidName, Object still, Object flowing, Object overlay, int color) {
        this(fluidName, still, flowing, overlay);
        this.color = color;
    }

    /** Links a fluid block to its definition; the block stand-in calls this from its constructor. */
    public static void attachBlock(Object fluid, Object block) {
        if (fluid instanceof LegacyFluid legacy && legacy.block == null) legacy.block = block;
    }

    public LegacyFluid setUnlocalizedName(Object name) {
        this.unlocalizedName = String.valueOf(name);
        return this;
    }

    public LegacyFluid setBlock(Object block) {
        this.block = block;
        return this;
    }

    public LegacyFluid setLuminosity(int luminosity) {
        this.luminosity = luminosity;
        return this;
    }

    public LegacyFluid setDensity(int density) {
        this.density = density;
        return this;
    }

    public LegacyFluid setTemperature(int temperature) {
        this.temperature = temperature;
        return this;
    }

    public LegacyFluid setViscosity(int viscosity) {
        this.viscosity = viscosity;
        return this;
    }

    public LegacyFluid setGaseous(boolean gaseous) {
        this.isGaseous = gaseous;
        return this;
    }

    public LegacyFluid setRarity(Object rarity) {
        this.rarity = rarity;
        return this;
    }

    public LegacyFluid setColor(int color) {
        this.color = color;
        return this;
    }

    /** {@code setColor(java.awt.Color)}: read through {@code getRGB} so AWT is never linked here. */
    public LegacyFluid setColor(Object color) {
        if (color instanceof Number n) {
            this.color = n.intValue();
        } else if (color != null) {
            try {
                this.color = (Integer) color.getClass().getMethod("getRGB").invoke(color);
            } catch (ReflectiveOperationException | ClassCastException ignored) {
                // Not a color object; keep the previous color.
            }
        }
        return this;
    }

    public LegacyFluid setFillSound(Object sound) {
        this.fillSound = sound;
        return this;
    }

    public LegacyFluid setEmptySound(Object sound) {
        this.emptySound = sound;
        return this;
    }

    public final Object getName() {
        return fluidName;
    }

    public Object getUnlocalizedName() {
        return "fluid." + unlocalizedName;
    }

    public Object getBlock() {
        return block;
    }

    public boolean canBePlacedInWorld() {
        return block != null;
    }

    public Object getStill() {
        return still;
    }

    public Object getFlowing() {
        return flowing;
    }

    public Object getOverlay() {
        return overlay;
    }

    public Object getRarity() {
        return rarity;
    }

    public Object getFillSound() {
        return fillSound;
    }

    public Object getEmptySound() {
        return emptySound;
    }

    public int getLuminosity() {
        return luminosity;
    }

    public int getDensity() {
        return density;
    }

    public int getTemperature() {
        return temperature;
    }

    public int getViscosity() {
        return viscosity;
    }

    public boolean isGaseous() {
        return isGaseous;
    }

    public int getColor() {
        return color;
    }

    public boolean isLighterThanAir() {
        return density <= 0;
    }

    @Override
    public String toString() {
        return "LegacyFluid[" + fluidName + "]";
    }
}
