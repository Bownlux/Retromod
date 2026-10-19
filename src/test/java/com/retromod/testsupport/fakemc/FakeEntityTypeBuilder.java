/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc;

/**
 * Stands in for Minecraft's {@code EntityType.Builder} from 1.21.2 on, which builds only from a
 * key. The real type derives its translation key and loot table from that key.
 */
public final class FakeEntityTypeBuilder {

    public FakeEntityType build(FakeResourceKey key) {
        return new FakeEntityType(key);
    }
}
