/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** What an old mod's baked model implements after translation; the test loads Retromod's rebuilt interface under this name. */
public interface FakeOldModelBase extends FakeBlockStateModel, FakeFabricBlockStateModel {
    // Bodies only so the fixtures compile; the rebuilt interface supplies the real ones.
    @Override
    default void collectParts(FakeRandom random, java.util.List<FakeBlockStateModelPart> parts) {
        throw new AssertionError();
    }

    @Override
    default FakeMaterial.Baked particleMaterial() {
        throw new AssertionError();
    }

    @Override
    default int materialFlags() {
        throw new AssertionError();
    }
}
