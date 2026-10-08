/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stand-in for the 1.12.2 {@code ConfigCategory}; see {@link LegacyConfiguration}. */
public final class LegacyConfigCategory {

    private final String name;
    private final Map<String, LegacyConfigProperty> properties = new LinkedHashMap<>();

    public LegacyConfigCategory(Object name) {
        this.name = String.valueOf(name);
    }

    LegacyConfigProperty property(String key, Object def) {
        return properties.computeIfAbsent(key, k -> new LegacyConfigProperty(k, def));
    }

    public Object getName() {
        return name;
    }

    public Object getQualifiedName() {
        return name;
    }

    public LegacyConfigProperty get(Object key) {
        return properties.get(String.valueOf(key));
    }

    public boolean containsKey(Object key) {
        return properties.containsKey(String.valueOf(key));
    }

    public Object keySet() {
        return properties.keySet();
    }

    public Object values() {
        return properties.values();
    }

    public Object getValues() {
        return properties;
    }

    public Object getOrderedValues() {
        return properties.values();
    }

    public int size() {
        return properties.size();
    }

    public boolean isEmpty() {
        return properties.isEmpty();
    }

    public LegacyConfigCategory setComment(Object comment) {
        return this;
    }

    public LegacyConfigCategory setLanguageKey(Object key) {
        return this;
    }

    public LegacyConfigCategory setRequiresMcRestart(boolean value) {
        return this;
    }

    public LegacyConfigCategory setRequiresWorldRestart(boolean value) {
        return this;
    }

    public LegacyConfigCategory setShowInGui(boolean value) {
        return this;
    }

    public LegacyConfigCategory setPropertyOrder(Object order) {
        return this;
    }
}
