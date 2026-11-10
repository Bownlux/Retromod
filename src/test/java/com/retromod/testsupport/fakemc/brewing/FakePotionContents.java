/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.brewing;

import java.util.Optional;

/** A bottle's potion. */
public final class FakePotionContents {
    private final FakeHolder potion;

    public FakePotionContents(FakeHolder potion) {
        this.potion = potion;
    }

    public Optional<FakeHolder> potion() {
        return Optional.ofNullable(potion);
    }

    public static FakeItemStack createItemStack(FakeItem item, FakeHolder potion) {
        return new FakeItemStack(item, new FakePotionContents(potion));
    }
}
