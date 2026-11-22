/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code BlockBehaviour}: what its constructor derives from the id. */
public abstract class FakeBlockBehaviour {
    protected final FakeBlockProperties properties;
    protected final java.util.Optional<FakeResourceKey> drops;
    protected final String descriptionId;

    protected FakeBlockBehaviour(FakeBlockProperties properties) {
        this.properties = properties;
        this.drops = properties.effectiveDrops();
        this.descriptionId = properties.effectiveDescriptionId();
    }

    public FakeBlockProperties properties() {
        return properties;
    }

    public final java.util.Optional<FakeResourceKey> getLootTable() {
        return drops;
    }

    public final String getDescriptionId() {
        return descriptionId;
    }
}
