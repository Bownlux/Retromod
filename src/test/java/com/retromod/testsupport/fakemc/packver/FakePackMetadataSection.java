/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.packver;

/** Pack metadata: a description and the range of formats the pack supports. */
public final class FakePackMetadataSection {
    private final FakeComponent description;
    private final FakeInclusiveRange<FakePackFormat> supported;

    public FakePackMetadataSection(FakeComponent description, FakeInclusiveRange<FakePackFormat> supported) {
        this.description = description;
        this.supported = supported;
    }

    public FakeInclusiveRange<FakePackFormat> supported() {
        return supported;
    }
}
