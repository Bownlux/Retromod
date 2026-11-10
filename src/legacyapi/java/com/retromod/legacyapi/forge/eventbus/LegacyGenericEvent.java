/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.eventbus;

import java.lang.reflect.Type;

/** Forge 1.20.1's {@code GenericEvent}: a legacy event that names the type its listeners can filter on. */
public class LegacyGenericEvent<T> extends LegacyEvent {

    private Class<T> type;

    public LegacyGenericEvent() {}

    protected LegacyGenericEvent(Class<T> type) {
        this.type = type;
    }

    /** The type given to the constructor, or null for an event built without one. */
    public Type getGenericType() {
        return type;
    }
}
