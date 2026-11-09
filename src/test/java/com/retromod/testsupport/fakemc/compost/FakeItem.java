/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.compost;


/** An item with its registry holder. */
public class FakeItem implements FakeItemLike {
    private final FakeHolderReference holder;

    public FakeItem(String id) {
        holder = new FakeHolderReference(FakeResourceKey.create(null, FakeIdentifier.withDefaultNamespace(id)));
    }

    public FakeItem asItem() {
        return this;
    }

    public FakeHolderReference builtInRegistryHolder() {
        return holder;
    }
}
