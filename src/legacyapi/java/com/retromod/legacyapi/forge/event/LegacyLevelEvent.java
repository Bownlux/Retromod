/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.event;

import com.retromod.legacyapi.forge.eventbus.LegacyEvent;
import net.minecraft.world.level.LevelAccessor;

/** Forge 1.20.1's {@code LevelEvent} class, rebuilt for mod events that extend it. */
public class LegacyLevelEvent extends LegacyEvent {

    // Not Forge 26.2's LevelEvent interface: that one is an inheritable event with its own bus, which would hand
    // a mod's event to every listener of level loads and unloads on a bus the mod never posted to.
    private final LevelAccessor level;

    public LegacyLevelEvent(LevelAccessor level) {
        this.level = level;
    }

    public LevelAccessor getLevel() {
        return level;
    }
}
