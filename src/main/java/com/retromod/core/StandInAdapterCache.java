/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** The adapter kept for each value, by identity, holding neither alive; each converting addon gets a copy. */
public final class StandInAdapterCache {

    private final ReferenceQueue<Object> cleared = new ReferenceQueue<>();
    private final Map<StandInAdapterKey, WeakReference<Object>> adapters = new HashMap<>();

    /** The adapter kept for {@code value}, or null when none is alive. */
    public synchronized Object find(Object value) {
        expunge();
        WeakReference<Object> adapter = adapters.get(new StandInAdapterKey(value, null));
        return adapter == null ? null : adapter.get();
    }

    /** Keeps {@code adapter} for {@code value} unless a live one is kept already, and returns the one kept. */
    public synchronized Object keep(Object value, Object adapter) {
        Object kept = find(value);
        if (kept != null) return kept;
        // The adapter is held weakly too: it holds the value, so a strong hold here would keep both.
        adapters.put(new StandInAdapterKey(value, cleared), new WeakReference<>(adapter));
        return adapter;
    }

    /** How many values still have an entry, after dropping the ones collected. */
    public synchronized int size() {
        expunge();
        return adapters.size();
    }

    private void expunge() {
        for (Reference<?> key; (key = cleared.poll()) != null; ) {
            adapters.remove(key);
        }
    }
}
