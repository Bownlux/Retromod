/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.ingredienthost;

import com.retromod.shim.forge.eb7.BusGroup;

/** Stands in for FML's ModContainer: a mod id and its bus group, which is null for Minecraft. */
public class ModContainer {
    private final String modId;
    private final BusGroup group;

    public ModContainer(String modId, BusGroup group) {
        this.modId = modId;
        this.group = group;
    }

    public final String getModId() {
        return modId;
    }

    public BusGroup getModBusGroup() {
        return group;
    }
}
