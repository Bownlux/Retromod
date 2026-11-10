/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;

import java.util.Map;
import java.util.function.Function;

/** Rebuilds SignItem and BedItem, which 26.3 folded into plain block items, for mods built before 26.3. */
public final class LegacyRemovedBlockItems {

    static final String FIRST_HOST_WITHOUT = "26.3";
    static final String BLOCK_ITEM = "net/minecraft/world/item/BlockItem";
    // The removed class, then the rebuilt one that keeps its constructors.
    static final Map<String, String> REBUILT = Map.of(
            "net/minecraft/world/item/SignItem", RebuiltApiClasses.ROOT + "minecraft/world/item/LegacySignItem",
            "net/minecraft/world/item/BedItem", RebuiltApiClasses.ROOT + "minecraft/world/item/LegacyBedItem");

    private LegacyRemovedBlockItems() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        REBUILT.forEach((removed, rebuilt) -> {
            if (!hostLacks(host, removed)) return;
            RebuiltApiClasses.registerTree(t, rebuilt);
            t.registerClassRedirect(removed, rebuilt);
            // A mod built for a host that has the name again keeps it.
            t.limitClassRedirectToModsBefore(removed, FIRST_HOST_WITHOUT);
            // An old instanceof check still matches vanilla's and other mods' signs and beds.
            t.registerInstanceofHelper(rebuilt, rebuilt, "is" + removed.substring(removed.lastIndexOf('/') + 1));
        });
    }

    /** Whether the host lacks {@code removed}; with no host to read, whether the target is 26.3 or newer. */
    static boolean hostLacks(Function<String, ClassNode> host, String removed) {
        return hostLacks(host, removed, RetromodVersion.TARGET_MC_VERSION);
    }

    static boolean hostLacks(Function<String, ClassNode> host, String removed, String hostVersion) {
        if (host.apply(BLOCK_ITEM) == null) {
            return RetromodVersion.compareMcVersions(hostVersion, FIRST_HOST_WITHOUT) >= 0;
        }
        return host.apply(removed) == null;
    }
}
