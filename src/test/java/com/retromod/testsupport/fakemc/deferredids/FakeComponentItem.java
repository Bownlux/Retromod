/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for a 1.21.11 {@code Item}: its constructor builds its component map. */
public class FakeComponentItem {
    protected final String descriptionId;
    private FakeComponentMap components;

    public FakeComponentItem(FakeItemProperties properties) {
        descriptionId = properties.effectiveDescriptionId();
        components = properties.buildComponents(FakeComponent.translatable(descriptionId), properties.effectiveModel());
    }

    public final String getDescriptionId() {
        return descriptionId;
    }

    public FakeComponentMap components() {
        return components;
    }
}
