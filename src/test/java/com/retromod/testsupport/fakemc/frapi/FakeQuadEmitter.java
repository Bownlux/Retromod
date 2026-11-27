/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Stands in for Fabric API's QuadEmitter. */
public interface FakeQuadEmitter extends FakeMutableQuadView {
    @Override
    FakeQuadEmitter cullFace(FakeDirection face);

    @Override
    FakeQuadEmitter fromBakedQuad(FakeBakedQuad quad);

    FakeQuadEmitter emit();

    void pushTransform(FakeQuadTransform transform);

    void popTransform();
}
