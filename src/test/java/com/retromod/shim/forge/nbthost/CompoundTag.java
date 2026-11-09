/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.nbthost;

import java.util.HashMap;
import java.util.Map;

/** Stands in for Minecraft's CompoundTag: named tags, with the put and get every version has. */
public final class CompoundTag implements Tag {
    private final Map<String, Tag> entries = new HashMap<>();

    public CompoundTag() {}

    public Tag put(String key, Tag value) {
        return entries.put(key, value);
    }

    public Tag get(String key) {
        return entries.get(key);
    }

    public boolean contains(String key) {
        return entries.containsKey(key);
    }
}
