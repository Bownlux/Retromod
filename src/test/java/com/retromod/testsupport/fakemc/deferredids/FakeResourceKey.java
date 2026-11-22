/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code ResourceKey}: interned per registry and id, as the host interns them. */
public final class FakeResourceKey {
    private static final java.util.Map<String, FakeResourceKey> INTERNED = new java.util.concurrent.ConcurrentHashMap<>();
    private final String registry;
    private final FakeIdentifier id;

    private FakeResourceKey(String registry, FakeIdentifier id) {
        this.registry = registry;
        this.id = id;
    }

    public static FakeResourceKey create(FakeResourceKey registry, FakeIdentifier id) {
        String name = registry == null ? "root" : registry.id.toString();
        return INTERNED.computeIfAbsent(name + "/" + id, unused -> new FakeResourceKey(name, id));
    }

    public FakeIdentifier identifier() {
        return id;
    }

    @Override
    public String toString() {
        return "ResourceKey[" + registry + " / " + id + "]";
    }
}
