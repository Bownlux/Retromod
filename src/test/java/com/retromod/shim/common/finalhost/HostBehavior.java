/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.finalhost;

/** Stands in for a host class that made a method final after old mods overrode it. */
public class HostBehavior {

    public final String label() {
        return "host";
    }

    /** Final too, but the test mod's own access transformer or widener opens it. */
    public final String title() {
        return "host";
    }
}
