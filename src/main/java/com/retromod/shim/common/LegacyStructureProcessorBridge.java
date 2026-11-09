/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;

/** Keeps a mod's structure processors working where 26.2 made the processor an interface keyed by its codec. */
public final class LegacyStructureProcessorBridge {

    static final String PROCESSOR = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureProcessor";
    static final String BASE = RebuiltApiClasses.ROOT
            + "minecraft/world/level/levelgen/structure/templatesystem/LegacyStructureProcessor";

    private LegacyStructureProcessorBridge() {}

    /** Registers from the 26.1 to 26.2 moves, so only a host with the interface takes it. */
    public static void register(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, BASE);
        // Only a mod built before 26.2 can extend the name, which is an interface from 26.2. The rebuilt base
        // keeps the old processBlock and getType, which the mod overrides, and answers the interface's
        // processBlock and codec from them; without it the processor never ran and had no codec.
        t.registerSuperclassRebase(PROCESSOR, BASE);
    }
}
