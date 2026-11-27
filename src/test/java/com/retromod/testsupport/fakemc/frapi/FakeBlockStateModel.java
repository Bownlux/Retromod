/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Stands in for BlockStateModel. */
public interface FakeBlockStateModel {
    void collectParts(FakeRandom random, java.util.List<FakeBlockStateModelPart> parts);

    FakeMaterial.Baked particleMaterial();

    int materialFlags();
}
