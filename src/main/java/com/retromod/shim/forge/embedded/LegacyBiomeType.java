/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stands in for 1.12.2's {@code BiomeDictionary.Type}, which 1.19 replaced with biome tags. Each
 * type names the tags that now mark the same biomes; see {@link LegacyBiomeDictionary}.
 *
 * <p>Calls on this class are erased to {@code Object}, so {@link #getName()} declares it.
 */
public final class LegacyBiomeType {
    private static final Map<String, LegacyBiomeType> BY_NAME = new ConcurrentHashMap<>();
    private static final List<LegacyBiomeType> ALL = Collections.synchronizedList(new ArrayList<>());

    /** Tag paths that differ from {@code is_<name>}. */
    private static final Map<String, String[]> TAG_PATHS = Map.of(
            "MESA", new String[] {"is_badlands"},
            "WATER", new String[] {"is_aquatic", "is_water"},
            "SPARSE", new String[] {"is_sparse_vegetation", "is_sparse"},
            "DENSE", new String[] {"is_dense_vegetation", "is_dense"},
            "CONIFEROUS", new String[] {"is_coniferous_tree", "is_coniferous", "is_taiga"},
            "SANDY", new String[] {"is_sandy", "is_desert"},
            "MOUNTAIN", new String[] {"is_mountain", "is_mountain_peak", "is_mountain_slope"},
            "HILLS", new String[] {"is_hill"});

    public static final LegacyBiomeType HOT = getType("HOT");
    public static final LegacyBiomeType COLD = getType("COLD");
    public static final LegacyBiomeType SPARSE = getType("SPARSE");
    public static final LegacyBiomeType DENSE = getType("DENSE");
    public static final LegacyBiomeType WET = getType("WET");
    public static final LegacyBiomeType DRY = getType("DRY");
    public static final LegacyBiomeType SAVANNA = getType("SAVANNA");
    public static final LegacyBiomeType CONIFEROUS = getType("CONIFEROUS");
    public static final LegacyBiomeType JUNGLE = getType("JUNGLE");
    public static final LegacyBiomeType SPOOKY = getType("SPOOKY");
    public static final LegacyBiomeType DEAD = getType("DEAD");
    public static final LegacyBiomeType LUSH = getType("LUSH");
    public static final LegacyBiomeType NETHER = getType("NETHER");
    public static final LegacyBiomeType END = getType("END");
    public static final LegacyBiomeType MUSHROOM = getType("MUSHROOM");
    public static final LegacyBiomeType MAGICAL = getType("MAGICAL");
    public static final LegacyBiomeType RARE = getType("RARE");
    public static final LegacyBiomeType OCEAN = getType("OCEAN");
    public static final LegacyBiomeType RIVER = getType("RIVER");
    public static final LegacyBiomeType WATER = getType("WATER");
    public static final LegacyBiomeType MESA = getType("MESA");
    public static final LegacyBiomeType FOREST = getType("FOREST");
    public static final LegacyBiomeType PLAINS = getType("PLAINS");
    public static final LegacyBiomeType MOUNTAIN = getType("MOUNTAIN");
    public static final LegacyBiomeType HILLS = getType("HILLS");
    public static final LegacyBiomeType SWAMP = getType("SWAMP");
    public static final LegacyBiomeType SANDY = getType("SANDY");
    public static final LegacyBiomeType SNOWY = getType("SNOWY");
    public static final LegacyBiomeType WASTELAND = getType("WASTELAND");
    public static final LegacyBiomeType BEACH = getType("BEACH");
    public static final LegacyBiomeType VOID = getType("VOID");
    public static final LegacyBiomeType OVERWORLD = getType("OVERWORLD");

    private final String name;
    private final String[] tags;

    private LegacyBiomeType(String name) {
        this.name = name;
        String[] paths = TAG_PATHS.getOrDefault(name, new String[] {"is_" + name.toLowerCase(Locale.ROOT)});
        List<String> tags = new ArrayList<>();
        for (String path : paths) {
            for (String namespace : new String[] {"c:", "forge:", "minecraft:"}) tags.add(namespace + path);
        }
        this.tags = tags.toArray(new String[0]);
    }

    /** {@code Type.getType(name, subTypes...)}: the type with this name, made on first use. */
    public static LegacyBiomeType getType(Object name, Object subTypes) {
        return getType(String.valueOf(name).toUpperCase(Locale.ROOT));
    }

    private static LegacyBiomeType getType(String name) {
        return BY_NAME.computeIfAbsent(name, key -> {
            LegacyBiomeType type = new LegacyBiomeType(key);
            ALL.add(type);
            return type;
        });
    }

    /** {@code Type.getAll()}. */
    public static Object getAll() {
        synchronized (ALL) {
            return List.copyOf(ALL);
        }
    }

    public Object getName() {
        return name;
    }

    /** Whether a biome tag with this id marks this type, such as {@code c:is_hot/overworld} for HOT. */
    public boolean matchesTag(String tagId) {
        for (String tag : tags) {
            if (tagId.equals(tag) || tagId.startsWith(tag + "/")) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return name;
    }
}
