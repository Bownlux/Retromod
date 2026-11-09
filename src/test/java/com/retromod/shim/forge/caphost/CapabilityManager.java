/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.caphost;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stands in for Forge 26.2's static CapabilityManager, whose package-private get registers and throws on a second registration. */
public final class CapabilityManager {
    static final Map<String, Capability<?>> PROVIDERS = new HashMap<>();
    public static final List<String> REGISTERED = new ArrayList<>();

    public CapabilityManager() {}

    @SuppressWarnings("unchecked")
    static <T> Capability<T> get(String name, Identifier id, boolean registering) {
        Capability<?> capability = PROVIDERS.computeIfAbsent(name, Capability::new);
        if (registering) {
            if (capability.isRegistered()) throw new IllegalStateException("Cannot register a capability twice: " + name);
            capability.onRegister();
            REGISTERED.add(name);
        }
        return (Capability<T>) capability;
    }
}
