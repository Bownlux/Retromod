/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Stands in for BlockStateModelPart. */
public interface FakeBlockStateModelPart {
    java.util.List<FakeBakedQuad> getQuads(FakeDirection side);

    boolean useAmbientOcclusion();

    FakeMaterial.Baked particleMaterial();

    int materialFlags();
}
