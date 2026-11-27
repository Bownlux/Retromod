/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

/** Answers Forge 1.20.1's {@code BuildCreativeModeTabContentsEvent.getEntries()} on NeoForge, which places tab entries itself. */
public final class LegacyTabEntriesBridge {

    static final String FORGE_MAP = "net/minecraftforge/common/util/MutableHashedLinkedMap";
    static final String NEO_MAP = "net/neoforged/neoforge/common/util/MutableHashedLinkedMap";
    static final String NEO_EVENT = "net/neoforged/neoforge/event/BuildCreativeModeTabContentsEvent";
    static final String FORGE_EVENT = "net/minecraftforge/event/BuildCreativeModeTabContentsEvent";
    static final String ENTRIES = RebuiltApiClasses.ROOT + "neoforge/event/LegacyTabEntries";

    private LegacyTabEntriesBridge() {}

    /** NeoForge 1.21 and newer, whose event has no entry map; NeoForge 1.20.2 to 1.20.4 kept one under its own name. */
    public static void register(RetromodTransformer t) {
        if (!entriesGone()) return;
        RebuiltApiClasses.registerTree(t, ENTRIES);
        // NeoForge kept Forge's map under its own name, so a map a mod builds itself stays a real map.
        t.registerClassRedirect(FORGE_MAP, NEO_MAP);
        t.registerClassRedirect(FORGE_MAP + "$MergeFunction", NEO_MAP + "$MergeFunction");
        String getEntries = "()L" + FORGE_MAP + ";";
        for (String event : List.of(NEO_EVENT, FORGE_EVENT)) {
            t.registerMethodRedirect(event, "getEntries", getEntries, ENTRIES, "of", "(L" + NEO_EVENT + ";)L" + NEO_MAP + ";", true);
        }
    }

    /** Whether the running host's event lacks {@code getEntries}; offline, the 1.21 shim that calls this has answered. */
    static boolean entriesGone() {
        ClassNode event = ClassResourceInspector.read(NEO_EVENT);
        return event == null || event.methods.stream().noneMatch(m -> m.name.equals("getEntries"));
    }
}
