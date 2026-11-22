/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.model;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.geom.ModelPart;

/** The children of an old model's stand-in root, read from the model once it has built its parts after the base. */
final class LegacyModelChildren extends AbstractMap<String, ModelPart> {

    // Set once the base constructor has run; before that the model has no parts to give.
    LegacyOldEntityModel<?> owner;
    // The model's parts never change once built, so the first non-empty answer is kept; every frame reads it.
    private Map<String, ModelPart> parts;

    @Override
    public Set<Entry<String, ModelPart>> entrySet() {
        if (parts != null) return parts.entrySet();
        if (owner == null) return Collections.emptySet();
        Map<String, ModelPart> found = new LinkedHashMap<>();
        owner.collectParts(found);
        if (found.isEmpty()) return found.entrySet();
        parts = Collections.unmodifiableMap(found);
        owner.partsReady();
        return parts.entrySet();
    }
}
