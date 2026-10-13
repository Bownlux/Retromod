/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.forge.embedded;

import java.util.*;

/**
 * Reimplementation of OreDictionary (removed in The Flattening, 1.13).
 * Maps ore dictionary names to modern tag equivalents via reflection.
 *
 * Old mods call OreDictionary.registerOre("ingotIron", item) and
 * OreDictionary.getOres("ingotIron"). This shim translates those to
 * tag queries like #c:ingots/iron or #forge:ingots/iron.
 */
public class OreDictionaryShim {

    private static final Map<String, String> ORE_TO_TAG = new HashMap<>();

    static {
        // Common ore dictionary -> tag mappings
        ORE_TO_TAG.put("ingotIron", "c:ingots/iron");
        ORE_TO_TAG.put("ingotGold", "c:ingots/gold");
        ORE_TO_TAG.put("ingotCopper", "c:ingots/copper");
        ORE_TO_TAG.put("nuggetIron", "c:nuggets/iron");
        ORE_TO_TAG.put("nuggetGold", "c:nuggets/gold");
        ORE_TO_TAG.put("blockIron", "c:storage_blocks/iron");
        ORE_TO_TAG.put("blockGold", "c:storage_blocks/gold");
        ORE_TO_TAG.put("blockDiamond", "c:storage_blocks/diamond");
        ORE_TO_TAG.put("blockEmerald", "c:storage_blocks/emerald");
        ORE_TO_TAG.put("blockCopper", "c:storage_blocks/copper");
        ORE_TO_TAG.put("oreIron", "c:ores/iron");
        ORE_TO_TAG.put("oreGold", "c:ores/gold");
        ORE_TO_TAG.put("oreDiamond", "c:ores/diamond");
        ORE_TO_TAG.put("oreEmerald", "c:ores/emerald");
        ORE_TO_TAG.put("oreCopper", "c:ores/copper");
        ORE_TO_TAG.put("oreCoal", "c:ores/coal");
        ORE_TO_TAG.put("oreLapis", "c:ores/lapis");
        ORE_TO_TAG.put("oreRedstone", "c:ores/redstone");
        ORE_TO_TAG.put("dustRedstone", "c:dusts/redstone");
        ORE_TO_TAG.put("dustGlowstone", "c:dusts/glowstone");
        ORE_TO_TAG.put("gemDiamond", "c:gems/diamond");
        ORE_TO_TAG.put("gemEmerald", "c:gems/emerald");
        ORE_TO_TAG.put("gemLapis", "c:gems/lapis");
        ORE_TO_TAG.put("gemQuartz", "c:gems/quartz");
        ORE_TO_TAG.put("stickWood", "c:rods/wooden");
        ORE_TO_TAG.put("plankWood", "minecraft:planks");
        ORE_TO_TAG.put("logWood", "minecraft:logs");
        ORE_TO_TAG.put("slabWood", "minecraft:wooden_slabs");
        ORE_TO_TAG.put("stairWood", "minecraft:wooden_stairs");
        ORE_TO_TAG.put("treeSapling", "minecraft:saplings");
        ORE_TO_TAG.put("treeLeaves", "minecraft:leaves");
        ORE_TO_TAG.put("cropWheat", "c:crops/wheat");
        ORE_TO_TAG.put("cropPotato", "c:crops/potato");
        ORE_TO_TAG.put("cropCarrot", "c:crops/carrot");
        ORE_TO_TAG.put("dye", "c:dyes");
        ORE_TO_TAG.put("dyeRed", "c:dyes/red");
        ORE_TO_TAG.put("dyeBlue", "c:dyes/blue");
        ORE_TO_TAG.put("dyeGreen", "c:dyes/green");
        ORE_TO_TAG.put("dyeBlack", "c:dyes/black");
        ORE_TO_TAG.put("dyeWhite", "c:dyes/white");
        ORE_TO_TAG.put("dyeYellow", "c:dyes/yellow");
        ORE_TO_TAG.put("string", "c:strings");
        ORE_TO_TAG.put("leather", "c:leathers");
        ORE_TO_TAG.put("feather", "c:feathers");
        ORE_TO_TAG.put("chest", "c:chests");
        ORE_TO_TAG.put("cobblestone", "c:cobblestones");
        ORE_TO_TAG.put("stone", "c:stones");
        ORE_TO_TAG.put("sand", "minecraft:sand");
        ORE_TO_TAG.put("gravel", "minecraft:gravel");
        ORE_TO_TAG.put("glass", "c:glass_blocks");
        ORE_TO_TAG.put("glassPane", "c:glass_panes");
        ORE_TO_TAG.put("wool", "minecraft:wool");
    }

    /**
     * Get the modern tag name for an ore dictionary name.
     * @return The tag name, or null if no mapping exists
     */
    public static String getTagForOre(String oreName) {
        return ORE_TO_TAG.get(oreName);
    }

    // Old API methods, intercepted at bytecode level
    public static void registerOre(String name, Object ore) {
        // No-op in modern MC: tags are data-driven
    }

    /**
     * Empty: tag contents need a loaded registry. 1.12 callers expect a {@code NonNullList}, so
     * one is made when Minecraft is present.
     */
    public static List<Object> getOres(String name) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) Class.forName("net.minecraft.core.NonNullList", true,
                    OreDictionaryShim.class.getClassLoader()).getMethod("create").invoke(null);
            return list;
        } catch (ReflectiveOperationException | LinkageError absent) {
            return new ArrayList<>();
        }
    }

    /** 1.12's {@code getOreNames()} returned an array. */
    public static String[] getOreNames() {
        return ORE_TO_TAG.keySet().toArray(new String[0]);
    }

    public static boolean doesOreNameExist(String name) {
        return ORE_TO_TAG.containsKey(name);
    }
}
