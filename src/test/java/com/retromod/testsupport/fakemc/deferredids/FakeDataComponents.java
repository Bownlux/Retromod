/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code DataComponents}. */
public final class FakeDataComponents {
    public static final FakeComponentType ITEM_NAME = new FakeComponentType("item_name");
    public static final FakeComponentType ITEM_MODEL = new FakeComponentType("item_model");
    public static final FakeComponentType MAX_STACK_SIZE = new FakeComponentType("max_stack_size");

    private FakeDataComponents() {}
}
