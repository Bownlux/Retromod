/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.neoforge.embedded.LegacyLootFrom;
import org.objectweb.asm.tree.ClassNode;

/** Runs the removed Forge and NeoForge {@code Properties.lootFrom(block)} as a loot table override. */
public final class LegacyLootFromBridge {

    static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    static final String LOOT_FROM_DESC = "(Ljava/util/function/Supplier;)L" + PROPERTIES + ";";
    // The lite build drops forge/embedded, so the helper stays in the NeoForge embedded package.
    static final String HELPER = "com/retromod/shim/neoforge/embedded/LegacyLootFrom";
    static final String HELPER_DESC = "(Ljava/lang/Object;Ljava/util/function/Supplier;)Ljava/lang/Object;";

    private LegacyLootFromBridge() {}

    /** Sends {@code lootFrom(Supplier)} to the embedded helper on a host whose properties lack it. */
    public static void register(RetromodTransformer transformer) {
        // A host that still declares lootFrom keeps the mod's own call. Offline, the shim's 1.21.2
        // target decides, and every 1.21.2 and newer host has the overrideLootTable the helper calls.
        ClassNode host = ClassResourceInspector.read(PROPERTIES);
        if (host != null && host.methods.stream().anyMatch(method -> method.name.equals("lootFrom"))) return;
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyLootFrom.class);
        transformer.registerMethodRedirect(PROPERTIES, "lootFrom", LOOT_FROM_DESC, HELPER, "lootFrom", HELPER_DESC, true);
    }
}
