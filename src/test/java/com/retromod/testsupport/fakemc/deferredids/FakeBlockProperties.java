/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code BlockBehaviour.Properties}. */
public class FakeBlockProperties {
    private FakeResourceKey id;
    private boolean noLootTable;

    public static FakeBlockProperties of() {
        return new FakeBlockProperties();
    }

    public FakeBlockProperties setId(FakeResourceKey id) {
        this.id = id;
        return this;
    }

    public FakeBlockProperties noLootTable() {
        noLootTable = true;
        return this;
    }

    private FakeResourceKey blockIdOrThrow() {
        return java.util.Objects.requireNonNull(id, "Block id not set");
    }

    protected java.util.Optional<FakeResourceKey> effectiveDrops() {
        FakeResourceKey block = blockIdOrThrow();
        return noLootTable ? java.util.Optional.empty()
                : java.util.Optional.of(FakeResourceKey.create(FakeRegistries.LOOT_TABLE, block.identifier().withPrefix("blocks/")));
    }

    protected String effectiveDescriptionId() {
        FakeIdentifier location = blockIdOrThrow().identifier();
        return "block." + location.getNamespace() + "." + location.getPath();
    }
}
