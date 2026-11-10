/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.fmlhost;

import java.util.List;

/** Stands in for Forge 26.2's state lookup: a state by its name, or IllegalArgumentException for an unknown one. */
public final class ModStateManager {

    // Names Forge 26.2 registers. It has no OBJECT_HOLDERS, which Forge 1.20.1 ran between the first two registry states.
    private static final List<String> NAMES = List.of("CONSTRUCT", "CREATE_REGISTRIES", "INJECT_CAPABILITIES",
            "LOAD_REGISTRIES", "COMPLETE", "DONE");

    private ModStateManager() {}

    public static IModLoadingState findState(String name) {
        if (!NAMES.contains(name)) throw new IllegalArgumentException("No state named " + name);
        return () -> name;
    }
}
