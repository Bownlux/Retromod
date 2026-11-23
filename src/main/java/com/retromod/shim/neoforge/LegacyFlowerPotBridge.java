/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.tree.ClassNode;

/** Keeps NeoForge's old {@code FlowerPotBlock.addPlant} working on NeoForge 26.3, which removed it. */
public final class LegacyFlowerPotBridge {

    static final String POT = "net/minecraft/world/level/block/FlowerPotBlock";
    static final String POTS = RebuiltApiClasses.ROOT + "neoforge/registries/LegacyFlowerPots";
    static final String SUPPLIER = "Ljava/util/function/Supplier;";
    // The id class an old mod names, before and after the 26.x rename of ResourceLocation.
    static final String[] ID_TYPES = {"net/minecraft/resources/Identifier", "net/minecraft/resources/ResourceLocation"};
    static final String HELPER_DESC = "(L" + POT + ";Lnet/minecraft/resources/Identifier;" + SUPPLIER + ")V";

    private LegacyFlowerPotBridge() {}

    /** Registers the redirect only where the host's pot has the full pot map and no addPlant. */
    public static void register(RetromodTransformer t) {
        if (!hostDroppedAddPlant()) return;
        RebuiltApiClasses.registerTree(t, POTS);
        // Supplementaries adds its flax and other pots to the vanilla pot during common setup.
        for (String id : ID_TYPES) {
            t.registerMethodRedirect(POT, "addPlant", "(L" + id + ";" + SUPPLIER + ")V", POTS, "addPlant", HELPER_DESC, true);
        }
    }

    /** NeoForge 26.3 replaced addPlant with getFullPots(); an unreadable host decides by version. */
    static boolean hostDroppedAddPlant() {
        ClassNode pot = ClassResourceInspector.read(POT);
        if (pot == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        boolean addPlant = pot.methods.stream().anyMatch(m -> m.name.equals("addPlant"));
        boolean fullPots = pot.methods.stream().anyMatch(m -> m.name.equals("getFullPots"));
        return !addPlant && fullPots;
    }
}
