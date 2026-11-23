/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code Item.Properties}: the id and the rules the host derives an item's description and model from. */
public class FakeItemProperties {
    private FakeResourceKey id;
    private String prefix = "item";
    private String description;
    private int maxStackSize = 64;

    public FakeItemProperties setId(FakeResourceKey id) {
        this.id = id;
        return this;
    }

    public FakeItemProperties useBlockDescriptionPrefix() {
        prefix = "block";
        return this;
    }

    public FakeItemProperties overrideDescription(String description) {
        this.description = description;
        return this;
    }

    public FakeItemProperties stacksTo(int size) {
        maxStackSize = size;
        return this;
    }

    FakeResourceKey itemIdOrThrow() {
        return java.util.Objects.requireNonNull(id, "Item id not set");
    }

    protected String effectiveDescriptionId() {
        FakeIdentifier location = itemIdOrThrow().identifier();
        if (description != null) return description;
        return prefix + "." + location.getNamespace() + "." + location.getPath().replace('/', '.');
    }

    public FakeIdentifier effectiveModel() {
        return itemIdOrThrow().identifier();
    }

    FakeInitializer finalizeInitializer(FakeComponent name, FakeIdentifier model) {
        int size = maxStackSize;
        return (builder, lookup, key) -> builder.set(FakeDataComponents.MAX_STACK_SIZE, size)
                .set(FakeDataComponents.ITEM_NAME, name).set(FakeDataComponents.ITEM_MODEL, model);
    }

    FakeComponentMap buildComponents(FakeComponent name, FakeIdentifier model) {
        return FakeComponentMap.builder().set(FakeDataComponents.MAX_STACK_SIZE, maxStackSize)
                .set(FakeDataComponents.ITEM_NAME, name).set(FakeDataComponents.ITEM_MODEL, model).build();
    }
}
