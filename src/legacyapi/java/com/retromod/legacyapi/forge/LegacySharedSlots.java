/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Objects that every mod's copy of Retromod's rebuilt classes shares, kept in the JDK's system properties. */
public final class LegacySharedSlots {

    // Each translated jar carries its own relocated copies, which share no class, so they meet under a key.
    // The protocol is part of the key: a copy from a build with another layout keeps a slot of its own.
    private static final String PREFIX = "retromod.shared.";

    // This copy's own objects, for keys that hold something of another class.
    private static final Map<String, Object> PRIVATE = new ConcurrentHashMap<>();

    private LegacySharedSlots() {}

    /** The object every copy shares under {@code name} and {@code protocol}, made by {@code create} on first use. */
    public static <T> T get(String name, int protocol, Class<T> type, Supplier<? extends T> create) {
        // Its class must come from the JDK or the host, so every copy sees the same class.
        Object shared = System.getProperties().computeIfAbsent(PREFIX + name + ".v" + protocol, unused -> create.get());
        if (type.isInstance(shared)) return type.cast(shared);
        // Something else under the key leaves this copy with a private object, made once, as it had before sharing.
        Object own = PRIVATE.computeIfAbsent(PREFIX + name + ".v" + protocol, unused -> create.get());
        return type.cast(own);
    }
}
