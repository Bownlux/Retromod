/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.caphost;

import com.retromod.shim.forge.eb7.BusGroup;
import com.retromod.shim.forge.eb7.EventBus;

/** Stands in for Forge's NewRegistryEvent, with its own BUS on the game group. */
public final class NewRegistryEvent extends MutableEvent {
    public static final EventBus BUS = EventBus.create(BusGroup.DEFAULT, NewRegistryEvent.class);
}
