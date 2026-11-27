/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Stands in for 26.3's MutableQuadView, which has shadeDirectionOverride and no diffuseShade. */
public interface FakeMutableQuadView extends FakeQuadView {
    FakeMutableQuadView chunkLayer(FakeChunkSectionLayer layer);

    FakeMutableQuadView emissive(boolean emissive);

    FakeMutableQuadView ambientOcclusion(FakeTriState ao);

    FakeMutableQuadView shadeMode(FakeShadeMode mode);

    FakeMutableQuadView tintIndex(int tintIndex);

    FakeMutableQuadView shadeDirectionOverride(FakeDirection direction);

    FakeMutableQuadView cullFace(FakeDirection face);

    FakeMutableQuadView fromBakedQuad(FakeBakedQuad quad);
}
