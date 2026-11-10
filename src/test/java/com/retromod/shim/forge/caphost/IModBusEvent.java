/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.caphost;

import com.retromod.shim.forge.eb7.BusGroup;
import com.retromod.shim.forge.eb7.EventBus;

/** Stands in for FML's mod-bus marker, whose getBus makes or finds the type's bus in a mod's group. */
public interface IModBusEvent extends InheritableEvent {
    static EventBus getBus(BusGroup group, Class<?> type) {
        return EventBus.create(group, type);
    }
}
