/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Stand-in for the 1.12.2 {@code ASMDataTable} a mod reads from its pre-init event. MCreator
 * 1.12 mods find every content class through {@code getAll(annotation)}, so without it they load
 * and register nothing. The answer comes from the host loader's scan of the mod's own jar, which
 * records the same class, member and annotation values. Annotations in other mods' jars are not
 * included, unlike 1.12, which scanned every mod at once.
 */
public final class LegacyAsmDataTable {

    private final String modId;

    public LegacyAsmDataTable(String modId) {
        this.modId = modId;
    }

    /** Takes the annotation's binary class name, as 1.12 did ({@code a.b.Outer$Inner}). */
    public Set<LegacyAsmData> getAll(String annotationClassName) {
        Set<LegacyAsmData> out = new LinkedHashSet<>();
        if (annotationClassName == null) return out;
        String desc = "L" + annotationClassName.replace('.', '/') + ";";
        for (Object[] entry : LegacyForgeEvents.scannedAnnotations(modId, desc)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) entry[2];
            out.add(new LegacyAsmData(annotationClassName, (String) entry[0], (String) entry[1], values));
        }
        return out;
    }
}
