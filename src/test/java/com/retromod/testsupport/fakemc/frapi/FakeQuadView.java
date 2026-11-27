/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Stands in for Fabric API's QuadView, with the reads Retromod makes. */
public interface FakeQuadView {
    FakeChunkSectionLayer chunkLayer();

    boolean emissive();

    FakeTriState ambientOcclusion();

    FakeShadeMode shadeMode();
}
