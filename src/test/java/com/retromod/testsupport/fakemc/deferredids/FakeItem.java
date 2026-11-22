/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for a 26.1 or newer {@code Item}: its constructor queues its components under its id. */
public class FakeItem {
    protected final String descriptionId;

    public FakeItem(FakeItemProperties properties) {
        descriptionId = properties.effectiveDescriptionId();
        FakeInitializer initializer = properties.finalizeInitializer(FakeComponent.translatable(descriptionId), properties.effectiveModel());
        FakeBuiltInRegistries.DATA_COMPONENT_INITIALIZERS.add(properties.itemIdOrThrow(), initializer);
    }

    public final String getDescriptionId() {
        return descriptionId;
    }

    public FakeComponentMap components() {
        throw new UnsupportedOperationException("26.1 items keep no component map");
    }
}
