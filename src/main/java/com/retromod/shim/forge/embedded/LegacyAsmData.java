/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.util.Map;

/** Stand-in for 1.12.2 {@code ASMDataTable.ASMData}: one annotated class or member. */
public final class LegacyAsmData {

    private final String annotationName;
    private final String className;
    private final String objectName;
    private final Map<String, Object> annotationInfo;

    public LegacyAsmData(String annotationName, String className, String objectName,
                         Map<String, Object> annotationInfo) {
        this.annotationName = annotationName;
        this.className = className;
        this.objectName = objectName;
        this.annotationInfo = annotationInfo == null ? Map.of() : annotationInfo;
    }

    public String getAnnotationName() {
        return annotationName;
    }

    public String getClassName() {
        return className;
    }

    /** The annotated member's name, or the class name for a class annotation, as in 1.12. */
    public String getObjectName() {
        return objectName;
    }

    public Map<String, Object> getAnnotationInfo() {
        return annotationInfo;
    }
}
