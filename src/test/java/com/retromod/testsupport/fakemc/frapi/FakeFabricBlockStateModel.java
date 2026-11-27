/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Stands in for FabricBlockStateModel; its default draw records the model it ran for. */
public interface FakeFabricBlockStateModel {
    java.util.List<Object> HOST_DRAWS = new java.util.concurrent.CopyOnWriteArrayList<>();

    default void emitQuads(FakeQuadEmitter emitter, FakeBlockAndTintGetter level, FakeBlockPos pos, FakeBlockState state, FakeRandom random,
            java.util.function.Predicate<FakeDirection> cullTest) {
        HOST_DRAWS.add(this);
    }
}
