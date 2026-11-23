/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code DataComponentInitializers}: a private list of keyed initializers, built once registries load. */
public class FakeComponentInitializers {
    private final java.util.List<FakeInitializerEntry> initializers = new java.util.ArrayList<>();

    public void add(FakeResourceKey key, FakeInitializer initializer) {
        initializers.add(new FakeInitializerEntry(key, initializer));
    }

    /** Runs every initializer in order; a key the registry lacks stops the build, as the host lookup does. */
    public java.util.Map<FakeResourceKey, FakeComponentMap> build(java.util.Set<FakeResourceKey> registered) {
        java.util.Map<FakeResourceKey, FakeComponentMapBuilder> builders = new java.util.LinkedHashMap<>();
        for (FakeInitializerEntry entry : initializers) {
            if (!registered.contains(entry.key())) throw new IllegalStateException("Missing element " + entry.key());
            entry.initializer().run(builders.computeIfAbsent(entry.key(), unused -> new FakeComponentMapBuilder()), null, entry.key());
        }
        java.util.Map<FakeResourceKey, FakeComponentMap> built = new java.util.HashMap<>();
        builders.forEach((key, builder) -> built.put(key, builder.build()));
        return built;
    }
}
