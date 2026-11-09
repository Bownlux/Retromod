/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.compost;


import java.util.ArrayList;
import java.util.List;

/** The game's component initializers, and the build that binds them, as a world load runs it. */
public class FakeComponentInitializers {
    private final List<Object[]> entries = new ArrayList<>();

    public void add(FakeResourceKey key, FakeInitializer initializer) {
        entries.add(new Object[] {key, initializer});
    }

    /** The item's own initializer, as vanilla registers one in the item's constructor. */
    public void addPreset(FakeResourceKey key, String path) {
        FakeCompostable compostable = new FakeCompostable(FakeResourceKey.create(FakeRegistries.CONTEXT_INT_PROVIDER,
                FakeIdentifier.withDefaultNamespace(path)));
        add(key, (builder, lookup, itemKey) -> builder.set(FakeComponents.COMPOSTABLE, compostable));
    }

    /** Runs every initializer of each item in order on a new builder and binds the result. */
    public void buildAndApply(List<FakeItem> items) {
        for (FakeItem item : items) {
            FakeHolderReference holder = item.builtInRegistryHolder();
            FakeComponentMapBuilder builder = FakeComponentMap.builder();
            for (Object[] entry : entries) {
                if (entry[0] == holder.key()) ((FakeInitializer) entry[1]).run(builder, null, holder.key());
            }
            holder.bindComponents(builder.build());
        }
    }
}
