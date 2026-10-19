/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

/** A real class for the ownership tests to redirect to, so no sweep drops it as a phantom. */
public final class OwnershipTarget {

    private OwnershipTarget() {}

    public static Object beforeRender(Object screen) {
        return null;
    }
}
