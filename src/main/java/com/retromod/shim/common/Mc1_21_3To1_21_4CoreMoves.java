/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;

/** Maps the vanilla members 1.21.4 renamed or removed, for every loader's 1.21.3 to 1.21.4 shim. */
public final class Mc1_21_3To1_21_4CoreMoves {

    static final String FURNACE = "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity";
    static final String FURNACE_INTERMEDIARY = "net/minecraft/class_2609";

    /** The furnace timers 1.21.4 renamed: old name, new name, and their intermediary ids. */
    static final String[][] FURNACE_FIELDS = {
            {"litTime", "litTimeRemaining", "field_11981", "field_55574"},
            {"litDuration", "litTotalTime", "field_11980", "field_55575"},
            {"cookingProgress", "cookingTimer", "field_11989", "field_55576"},
    };

    private Mc1_21_3To1_21_4CoreMoves() {}

    public static void register(RetromodTransformer t) {
        registerFurnaceFieldRenames(t);
        registerBlockAtlasMove(t);
        // The foliage color getters became constants.
        Legacy1214MemberBridge.register(t);
    }

    /** InventoryMenu.BLOCK_ATLAS, removed in 1.21.4, read from TextureAtlas.LOCATION_BLOCKS, which names the same atlas. */
    static void registerBlockAtlasMove(RetromodTransformer t) {
        // 1.21.3 set LOCATION_BLOCKS from BLOCK_ATLAS. The read keeps its own descriptor, which names
        // ResourceLocation or Identifier as the host does. Only the Mojang name is keyed: the
        // intermediary id has no renamed twin.
        t.registerFieldRedirect("net/minecraft/world/inventory/InventoryMenu", "BLOCK_ATLAS",
                "net/minecraft/client/renderer/texture/TextureAtlas", "LOCATION_BLOCKS");
    }

    /**
     * The furnace's burn and cook timers kept their meaning and type under new names. Mods reach
     * them through accessor mixins, whose names follow these redirects, Jade's furnace progress
     * among them. The intermediary ids serve Fabric hosts before 26.1, which run those names.
     */
    static void registerFurnaceFieldRenames(RetromodTransformer t) {
        for (String[] field : FURNACE_FIELDS) {
            t.registerFieldRedirect(FURNACE, field[0], "I", FURNACE, field[1], "I");
            t.registerFieldRedirect(FURNACE_INTERMEDIARY, field[2], "I", FURNACE_INTERMEDIARY, field[3], "I");
        }
    }
}
