/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.util.Locale;
import java.util.Map;

/** Stands in for 1.18's {@code Biome.BiomeCategory}, which 1.19 replaced with biome tags; a biome's category comes from its id. */
public enum LegacyBiomeCategory {
    NONE, TAIGA, EXTREME_HILLS, JUNGLE, MESA, PLAINS, SAVANNA, ICY, THEEND, BEACH, FOREST, OCEAN, DESERT, RIVER, SWAMP,
    MUSHROOM, NETHER, UNDERGROUND, MOUNTAIN;

    /** The category each vanilla biome carried in its 1.18 data, plus the biomes added since, by their closest category. */
    private static final Map<String, LegacyBiomeCategory> VANILLA = Map.ofEntries(
            Map.entry("ocean", OCEAN), Map.entry("deep_ocean", OCEAN), Map.entry("cold_ocean", OCEAN),
            Map.entry("deep_cold_ocean", OCEAN), Map.entry("frozen_ocean", OCEAN), Map.entry("deep_frozen_ocean", OCEAN),
            Map.entry("lukewarm_ocean", OCEAN), Map.entry("deep_lukewarm_ocean", OCEAN), Map.entry("warm_ocean", OCEAN),
            Map.entry("plains", PLAINS), Map.entry("sunflower_plains", PLAINS), Map.entry("snowy_plains", ICY),
            Map.entry("ice_spikes", ICY), Map.entry("desert", DESERT), Map.entry("swamp", SWAMP),
            Map.entry("mangrove_swamp", SWAMP), Map.entry("forest", FOREST), Map.entry("flower_forest", FOREST),
            Map.entry("birch_forest", FOREST), Map.entry("old_growth_birch_forest", FOREST), Map.entry("dark_forest", FOREST),
            Map.entry("pale_garden", FOREST), Map.entry("taiga", TAIGA), Map.entry("snowy_taiga", TAIGA),
            Map.entry("old_growth_pine_taiga", TAIGA), Map.entry("old_growth_spruce_taiga", TAIGA), Map.entry("jungle", JUNGLE),
            Map.entry("sparse_jungle", JUNGLE), Map.entry("bamboo_jungle", JUNGLE), Map.entry("savanna", SAVANNA),
            Map.entry("savanna_plateau", SAVANNA), Map.entry("windswept_savanna", SAVANNA), Map.entry("badlands", MESA),
            Map.entry("eroded_badlands", MESA), Map.entry("wooded_badlands", MESA), Map.entry("windswept_hills", EXTREME_HILLS),
            Map.entry("windswept_gravelly_hills", EXTREME_HILLS), Map.entry("windswept_forest", EXTREME_HILLS),
            Map.entry("meadow", MOUNTAIN), Map.entry("cherry_grove", MOUNTAIN), Map.entry("grove", MOUNTAIN),
            Map.entry("snowy_slopes", MOUNTAIN), Map.entry("jagged_peaks", MOUNTAIN), Map.entry("frozen_peaks", MOUNTAIN),
            Map.entry("stony_peaks", MOUNTAIN), Map.entry("river", RIVER), Map.entry("frozen_river", RIVER),
            Map.entry("beach", BEACH), Map.entry("snowy_beach", BEACH), Map.entry("stony_shore", BEACH),
            Map.entry("mushroom_fields", MUSHROOM), Map.entry("dripstone_caves", UNDERGROUND),
            Map.entry("lush_caves", UNDERGROUND), Map.entry("deep_dark", UNDERGROUND), Map.entry("nether_wastes", NETHER),
            Map.entry("soul_sand_valley", NETHER), Map.entry("crimson_forest", NETHER), Map.entry("warped_forest", NETHER),
            Map.entry("basalt_deltas", NETHER), Map.entry("the_end", THEEND), Map.entry("small_end_islands", THEEND),
            Map.entry("end_midlands", THEEND), Map.entry("end_highlands", THEEND), Map.entry("end_barrens", THEEND),
            Map.entry("the_void", NONE));

    /** Words in another mod's biome id, checked in this order, and the category each suggests. */
    private static final String[][] WORDS = {
        {"nether", "NETHER"}, {"crimson", "NETHER"}, {"warped", "NETHER"}, {"basalt", "NETHER"}, {"end", "THEEND"},
        {"mushroom", "MUSHROOM"}, {"ocean", "OCEAN"}, {"beach", "BEACH"}, {"shore", "BEACH"},
        {"river", "RIVER"}, {"swamp", "SWAMP"}, {"marsh", "SWAMP"}, {"bog", "SWAMP"}, {"jungle", "JUNGLE"},
        {"rainforest", "JUNGLE"}, {"badlands", "MESA"}, {"mesa", "MESA"}, {"savanna", "SAVANNA"}, {"desert", "DESERT"},
        {"dune", "DESERT"}, {"taiga", "TAIGA"}, {"tundra", "ICY"}, {"frozen", "ICY"}, {"snow", "ICY"}, {"ice", "ICY"}, {"icy", "ICY"},
        {"glacier", "ICY"}, {"peak", "MOUNTAIN"}, {"mountain", "MOUNTAIN"}, {"alp", "MOUNTAIN"}, {"hill", "EXTREME_HILLS"},
        {"cave", "UNDERGROUND"}, {"forest", "FOREST"}, {"wood", "FOREST"}, {"grove", "FOREST"}, {"plain", "PLAINS"},
        {"meadow", "PLAINS"}, {"prairie", "PLAINS"}, {"field", "PLAINS"}, {"steppe", "PLAINS"},
    };

    /** The category's name as 1.18 serialized it. */
    public String getName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The category named {@code name}, or null. */
    public static LegacyBiomeCategory byName(String name) {
        for (LegacyBiomeCategory category : values()) {
            if (category.getName().equals(name)) return category;
        }
        return null;
    }

    /** The category a biome with this id would have had: vanilla's own, else one guessed from the words in the id. */
    public static LegacyBiomeCategory of(Object biomeId) {
        if (biomeId == null) return NONE;
        String id = biomeId.toString();
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        if (namespace.equals("minecraft")) return VANILLA.getOrDefault(path, NONE);
        String[] parts = path.split("[_/]");
        for (String[] word : WORDS) {
            for (String part : parts) {
                // A short word must be the whole part, so rice is no ice and legend no end.
                if (part.equals(word[0]) || (word[0].length() > 3 && part.startsWith(word[0]))) return valueOf(word[1]);
            }
        }
        return NONE;
    }
}
