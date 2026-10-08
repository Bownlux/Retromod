/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.capability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for Forge's {@code CapabilityManager}. {@code get(token)} returns one capability per
 * token type, so every {@code new CapabilityToken<MyData>(){}} in a mod resolves to the same
 * token object, as it did on Forge.
 */
public final class LegacyCapabilityManager {

    private static final Map<String, LegacyCapability<?>> BY_TYPE = new ConcurrentHashMap<>();

    private LegacyCapabilityManager() {}

    @SuppressWarnings("unchecked")
    public static <T> LegacyCapability<T> get(LegacyCapabilityToken<T> token) {
        return (LegacyCapability<T>) BY_TYPE.computeIfAbsent(token.getType(), LegacyCapability::new);
    }
}
