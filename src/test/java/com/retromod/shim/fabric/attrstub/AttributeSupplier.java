/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.attrstub;

import java.util.Map;

/** Renamed to {@code net/minecraft/class_5132}: default instances keyed by holder. */
public final class AttributeSupplier {
    private final Map<Object, Object> instances;

    public AttributeSupplier(Map<Object, Object> instances) {
        this.instances = instances;
    }

    /** {@code createInstance}: null when the defaults have no entry for this holder. */
    public Object instanceFor(Object holder) {
        return instances.get(holder);
    }
}
