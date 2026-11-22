/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.settings;

import net.neoforged.neoforge.client.settings.KeyModifier;

import java.util.ArrayList;
import java.util.List;

/** Forge 1.20.1's {@code KeyModifier.getActiveModifier} and {@code getValues} on NeoForge's modifiers. */
public final class LegacyKeyModifiers {

    private LegacyKeyModifiers() {}

    /** The first held modifier, or {@code NONE}, as Forge answered with one modifier. */
    public static KeyModifier getActiveModifier() {
        List<KeyModifier> active = KeyModifier.getActiveModifiers();
        return active.isEmpty() ? KeyModifier.NONE : active.get(0);
    }

    /** Every modifier, leaving out {@code NONE} when asked to ignore it. */
    public static List<KeyModifier> getValues(boolean ignoreNone) {
        List<KeyModifier> values = new ArrayList<>();
        for (KeyModifier modifier : KeyModifier.values()) {
            if (!ignoreNone || modifier != KeyModifier.NONE) values.add(modifier);
        }
        return values;
    }
}
