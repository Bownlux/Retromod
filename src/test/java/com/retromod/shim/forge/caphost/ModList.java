/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.caphost;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Stands in for Forge 26.2's static ModList: the loaded mods in their sorted order. */
public final class ModList {
    public static final List<ModContainer> MODS = new ArrayList<>();

    private ModList() {}

    public static void forEachModInOrder(Consumer<ModContainer> action) {
        MODS.forEach(action);
    }
}
