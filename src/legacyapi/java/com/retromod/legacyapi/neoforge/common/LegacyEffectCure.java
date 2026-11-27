/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.common;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** NeoForge 1.21.1's {@code EffectCure}, a named way to end an effect, rebuilt for hosts that dropped cures. */
public final class LegacyEffectCure {

    // One instance per name, as the old class interned them, so identity checks still match.
    private static final Map<String, LegacyEffectCure> CURES = new ConcurrentHashMap<>();

    private final String name;

    private LegacyEffectCure(String name) {
        this.name = name;
    }

    /** {@code EffectCure.get(name)}: the cure with this name, made on first use. */
    public static LegacyEffectCure get(String name) {
        return CURES.computeIfAbsent(name, LegacyEffectCure::new);
    }

    /** {@code EffectCure.getAllCures()}. */
    public static Collection<LegacyEffectCure> getAllCures() {
        return Collections.unmodifiableCollection(CURES.values());
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "EffectCure[" + name + "]";
    }
}
