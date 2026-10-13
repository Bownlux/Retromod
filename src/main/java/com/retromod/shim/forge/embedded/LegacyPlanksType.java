/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stands in for 1.12.2's {@code BlockPlanks.EnumType}, the six wood variants that 1.13 split into
 * separate blocks. Mods used it to name their own log and plank variants; the names and old
 * metadata numbers are kept.
 */
public final class LegacyPlanksType {
    public static final LegacyPlanksType OAK = new LegacyPlanksType(0, "oak");
    public static final LegacyPlanksType SPRUCE = new LegacyPlanksType(1, "spruce");
    public static final LegacyPlanksType BIRCH = new LegacyPlanksType(2, "birch");
    public static final LegacyPlanksType JUNGLE = new LegacyPlanksType(3, "jungle");
    public static final LegacyPlanksType ACACIA = new LegacyPlanksType(4, "acacia");
    public static final LegacyPlanksType DARK_OAK = new LegacyPlanksType(5, "dark_oak");
    private static final LegacyPlanksType[] VALUES = {OAK, SPRUCE, BIRCH, JUNGLE, ACACIA, DARK_OAK};

    private final int meta;
    private final String name;

    private LegacyPlanksType(int meta, String name) {
        this.meta = meta;
        this.name = name;
    }

    /** Typed {@code Object}: calls on stand-ins are erased to that shape and cast back. */
    public static Object values() { return VALUES.clone(); }
    public static LegacyPlanksType byMetadata(int meta) { return VALUES[Math.floorMod(meta, VALUES.length)]; }
    public static LegacyPlanksType func_176837_a(int meta) { return byMetadata(meta); }
    public int getMetadata() { return meta; }
    public int func_176839_a() { return meta; }
    public String getName() { return name; }
    public String func_176610_l() { return name; }
    public String getSerializedName() { return name; }
    public String getTranslationKey() { return name; }
    public String func_176840_c() { return name; }
    public int ordinal() { return meta; }

    /** {@code getMapColor()}: wood's map color, the closest the merged wood blocks still have. */
    public Object getMapColor() {
        try {
            Class<?> color = Class.forName("net.minecraft.world.level.material.MapColor", true,
                    LegacyPlanksType.class.getClassLoader());
            for (String field : new String[]{"WOOD", "f_283825_"}) {
                try {
                    return color.getField(field).get(null);
                } catch (NoSuchFieldException next) {
                    // Forge 1.20.1 keeps SRG field names at runtime.
                }
            }
        } catch (ReflectiveOperationException | LinkageError missing) {
            return null;
        }
        return null;
    }
    public Object func_181070_c() { return getMapColor(); }

    @Override
    public String toString() { return name; }
}
