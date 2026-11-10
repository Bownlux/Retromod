/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.thirdparty;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;
import com.retromod.shim.api.common.LibraryMoves;

import java.util.LinkedHashMap;
import java.util.Map;

/** Moves Trinkets 2.x class names onto Trinkets 3.x where the Trinkets the mods run with has dropped them. */
public class TrinketsBridgePolyfill implements PolyfillProvider {

    // Package stays dev.emi.trinkets.api; TrinketSlotCenter has no successor, so it takes SlotGroup.
    static final Map<String, String> MOVES = orderedMoves(
            "dev/emi/trinkets/api/TrinketSlot", "dev/emi/trinkets/api/SlotType",
            "dev/emi/trinkets/api/TrinketSlots", "dev/emi/trinkets/api/SlotGroup",
            "dev/emi/trinkets/api/AbstractTrinket", "dev/emi/trinkets/api/TrinketItem",
            "dev/emi/trinkets/api/TrinketSlotCenter", "dev/emi/trinkets/api/SlotGroup");

    // Modrinth, 2026-11-07: every Trinkets for Minecraft 1.17 and newer is 3.x (3.0.0 to 3.10.0), and none of
    // 3.0.0, 3.7.2 and 3.10.0 ships the 2.x names, so a mod built from 1.17 never names them.
    static final LibraryMoves.Table TRINKETS_3 = new LibraryMoves.Table("1.17", "1.17");

    @Override
    public String getName() {
        return "Trinkets 2.x -> 3.x Bridge";
    }

    @Override
    public String getCategory() {
        return "thirdparty";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            "dev/emi/trinkets/api/TrinketSlot",
            "dev/emi/trinkets/api/TrinketSlots",
            "dev/emi/trinkets/api/AbstractTrinket",
            "dev/emi/trinkets/api/TrinketSlotCenter"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        // no embedded stubs; class redirects only
        return new String[]{};
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // Only where the Trinkets the mods run with lacks the 2.x names and has the 3.x ones.
        LibraryMoves.register(transformer, MOVES, TRINKETS_3);
    }

    private static Map<String, String> orderedMoves(String... pairs) {
        Map<String, String> moves = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) moves.put(pairs[i], pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(moves);
    }
}
