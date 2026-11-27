/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen;

/** The air and liquid carving steps that 1.21.2 removed when every carver moved into one list. */
public enum LegacyCarvingStep {
    AIR("air"),
    LIQUID("liquid");

    private final String name;

    LegacyCarvingStep(String name) {
        this.name = name;
    }

    /** The step's lowercase name, as the old step reported it. */
    public String getName() {
        return name;
    }
}
