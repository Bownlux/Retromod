/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.ingredienthost;

import com.retromod.shim.forge.caphost.IModBusEvent;
import com.retromod.shim.forge.eb7.BusGroup;
import com.retromod.shim.forge.eb7.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Stands in for Forge 26.2's RegisterEvent: one registry key, and every entry registered while it is posted. */
public class RegisterEvent implements IModBusEvent {
    private final ResourceKey key;
    /** Each registration as {key, id, value}, in order. */
    public final List<Object[]> registered = new ArrayList<>();

    public RegisterEvent(ResourceKey key) {
        this.key = key;
    }

    public static EventBus getBus(BusGroup group) {
        return EventBus.create(group, RegisterEvent.class);
    }

    public ResourceKey getRegistryKey() {
        return key;
    }

    public void register(ResourceKey registry, Identifier name, Supplier<?> value) {
        registered.add(new Object[]{registry, name, value.get()});
    }
}
