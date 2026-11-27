/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Stands in for TextureAtlasSprite: a rectangle of the atlas. */
public class FakeSprite {
    private final float u0, u1, v0, v1;

    public FakeSprite(float u0, float u1, float v0, float v1) {
        this.u0 = u0;
        this.u1 = u1;
        this.v0 = v0;
        this.v1 = v1;
    }

    public float getU0() {
        return u0;
    }

    public float getU1() {
        return u1;
    }

    public float getV0() {
        return v0;
    }

    public float getV1() {
        return v1;
    }
}
