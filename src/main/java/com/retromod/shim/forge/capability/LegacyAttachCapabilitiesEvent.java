/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stand-in for Forge's {@code AttachCapabilitiesEvent}. {@link LegacyCapabilitySynthetics} rebases
 * it onto NeoForge's {@code Event} so a mod's {@code @SubscribeEvent} method can still take it,
 * and retypes {@code addCapability} to Forge's {@code (ResourceLocation, ICapabilityProvider)}.
 * {@link LegacyCapabilityRuntime} posts it the first time a holder is asked for a capability.
 */
public class LegacyAttachCapabilitiesEvent<T> {

    private final T object;
    private final Map<Object, Object> capabilities = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    public LegacyAttachCapabilitiesEvent(T object) {
        this.object = object;
    }

    public T getObject() {
        return object;
    }

    public void addCapability(Object key, Object provider) {
        if (capabilities.containsKey(key)) {
            throw new IllegalStateException("Duplicate capability key: " + key + " " + provider);
        }
        capabilities.put(key, provider);
    }

    public Map<Object, Object> getCapabilities() {
        return Collections.unmodifiableMap(capabilities);
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public List<Runnable> getListeners() {
        return Collections.unmodifiableList(listeners);
    }
}
