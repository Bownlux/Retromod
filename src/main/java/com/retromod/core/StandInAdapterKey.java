/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/** A weak key for {@link StandInAdapterCache} that matches the same object only, never an equal one. */
public final class StandInAdapterKey extends WeakReference<Object> {

    private final int hash;

    public StandInAdapterKey(Object value, ReferenceQueue<Object> queue) {
        super(value, queue);
        this.hash = System.identityHashCode(value);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof StandInAdapterKey key)) return false;
        Object value = get();
        return value != null && value == key.get();
    }
}
