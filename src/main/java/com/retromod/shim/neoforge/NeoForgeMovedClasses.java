/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

import java.util.Map;

/** Follows NeoForge 1.21.1 classes that later NeoForge releases moved to another package, members unchanged. */
public final class NeoForgeMovedClasses {

    private static final String NEO = "net/neoforged/neoforge/";

    // Each old class is in the 21.1.95 universal jar, and its new home is in the 21.11.42, 26.1.2,
    // 26.2 and 26.3 jars with the same members once Minecraft's own renames are applied.
    static final Map<String, String> MOVES = Map.ofEntries(
            Map.entry(NEO + "client/event/AddAttributeTooltipsEvent", NEO + "event/AddAttributeTooltipsEvent"),
            Map.entry(NEO + "client/event/GatherSkippedAttributeTooltipsEvent", NEO + "event/GatherSkippedAttributeTooltipsEvent"),
            Map.entry(NEO + "client/extensions/IMenuProviderExtension", NEO + "common/extensions/IMenuProviderExtension"),
            Map.entry(NEO + "client/model/data/ModelData", NEO + "model/data/ModelData"),
            Map.entry(NEO + "client/model/data/ModelData$Builder", NEO + "model/data/ModelData$Builder"),
            Map.entry(NEO + "client/model/data/ModelProperty", NEO + "model/data/ModelProperty"),
            Map.entry(NEO + "client/model/data/ModelDataManager", NEO + "model/data/ModelDataManager"),
            Map.entry(NEO + "event/entity/player/PlayerHeartTypeEvent", NEO + "client/event/PlayerHeartTypeEvent"),
            Map.entry(NEO + "items/StackCopySlot", NEO + "world/inventory/StackCopySlot"),
            Map.entry(NEO + "network/handling/ClientPayloadContext", NEO + "client/network/handling/ClientPayloadContext"),
            Map.entry(NEO + "registries/ClientRegistryManager", NEO + "client/registries/ClientRegistryManager"),
            Map.entry(NEO + "internal/versions/neoforge/NeoForgeVersion", NEO + "common/NeoForgeVersion"),
            // NeoForge patches isTrue, isFalse and isDefault onto Minecraft's own TriState.
            Map.entry(NEO + "common/util/TriState", "net/minecraft/util/TriState"));

    /** The first host checked to have every new home; offline, older targets keep the old names. */
    static final String VERIFIED_FROM = "1.21.11";

    private NeoForgeMovedClasses() {}

    public static void register(RetromodTransformer t) {
        for (Map.Entry<String, String> move : MOVES.entrySet()) {
            if (movedOnHost(move.getKey(), move.getValue())) t.registerClassRedirect(move.getKey(), move.getValue());
        }
    }

    /** Read from the host when it can be; offline, the target version decides. */
    static boolean movedOnHost(String oldName, String newName) {
        if (ClassResourceInspector.exists(oldName)) return false;
        if (ClassResourceInspector.exists(newName)) return true;
        return !ClassResourceInspector.exists(NEO + "common/NeoForge")
                && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, VERIFIED_FROM) >= 0;
    }
}
