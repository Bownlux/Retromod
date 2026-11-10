/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.brewing;

/** A stack: an item and, for a bottle, its potion. */
public final class FakeItemStack {
    public static final FakeItemStack EMPTY = new FakeItemStack(null, null);

    private final FakeItem item;
    private final FakePotionContents contents;

    public FakeItemStack(FakeItem item, FakePotionContents contents) {
        this.item = item;
        this.contents = contents;
    }

    public FakeItem getItem() {
        return item;
    }

    public Object get(FakeDataComponentType type) {
        return type == FakeDataComponents.POTION_CONTENTS ? contents : null;
    }
}
