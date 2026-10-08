/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.attrstub;

/** {@code Holder.Reference}: the registered holder, equal only to itself. */
public final class ReferenceHolder implements Holder {
    private final Object value;

    public ReferenceHolder(Object value) {
        this.value = value;
    }

    @Override
    public Object comp_349() {
        return value;
    }
}
