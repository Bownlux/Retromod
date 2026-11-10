/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.color;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntBiFunction;

/** The one table of old item colors that every mod's copy shares, by item, as Minecraft kept one. */
public final class LegacyItemColorTable {

    // Layout 2 entries answer the color drawn, alpha included; a layout 1 copy forced alpha when it drew, so they part.
    /** The table's layout; a copy from a Retromod build with another layout keeps a table of its own. */
    public static final int PROTOCOL = 2;
    /** {@code Map<Item, ToIntBiFunction<ItemStack, Integer>>}: every old item color, by item, answering the color drawn. */
    public static final String TABLE = "forge.itemColors.table";
    /** The path of the item tint type each mod's namespace gets; Retromod's item definitions name it. */
    public static final String TINT_TYPE = "retromod_legacy_color";

    // Read for every tint of every drawn stack, so the shared slot is looked up once per copy.
    private static volatile Map<Object, ToIntBiFunction<Object, Integer>> table;

    private LegacyItemColorTable() {}

    /** The shared table, made on first use. */
    @SuppressWarnings("unchecked")
    public static Map<Object, ToIntBiFunction<Object, Integer>> table() {
        Map<Object, ToIntBiFunction<Object, Integer>> shared = table;
        if (shared == null) {
            shared = LegacySharedSlots.get(TABLE, PROTOCOL, Map.class, ConcurrentHashMap::new);
            table = shared;
        }
        return shared;
    }
}
