/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.ingredienthost;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Stands in for FML's mod list, which finds a container by mod id. */
public final class ModList {
    public static final List<ModContainer> MODS = new ArrayList<>();

    private ModList() {}

    public static Optional<ModContainer> getModContainerById(String modId) {
        return MODS.stream().filter(mod -> mod.getModId().equals(modId)).findFirst();
    }
}
