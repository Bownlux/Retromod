/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.capabilities;

import net.minecraftforge.common.capabilities.CapabilityManager;

/** Capability members Forge 26.2 removed with nothing left to do: the manager's instance and the stack comparison. */
public final class LegacyCapabilityCalls {

    private static volatile CapabilityManager instance;

    private LegacyCapabilityCalls() {}

    /** {@code CapabilityManager.INSTANCE}: Forge 26.2's manager is all static, so any instance stands in for it. */
    public static CapabilityManager instance() {
        CapabilityManager manager = instance;
        if (manager == null) {
            manager = new CapabilityManager();
            instance = manager;
        }
        return manager;
    }

    /** {@code stack.areCapsCompatible(other)}: Forge 26.2 compares stacks by their components and keeps no stack capability data. */
    public static boolean areCapsCompatible(Object stack, Object other) {
        return true;
    }
}
