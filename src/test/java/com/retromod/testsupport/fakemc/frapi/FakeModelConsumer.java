/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Stands in for the old context's BakedModelConsumer; the test loads Retromod's rebuilt one under this name. */
public interface FakeModelConsumer {
    void accept(FakeBlockStateModel model);
}
