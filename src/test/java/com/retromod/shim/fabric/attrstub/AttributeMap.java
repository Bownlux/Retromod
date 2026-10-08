/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.attrstub;

/** {@code AttributeMap}, which reads its defaults from the supplier field. */
public final class AttributeMap {
    private final AttributeSupplier supplier;

    public AttributeMap(AttributeSupplier supplier) {
        this.supplier = supplier;
    }

    public Object getInstance(Object holder) {
        return supplier.instanceFor(holder);
    }
}
