/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Stands in for Material and its baked form. */
public final class FakeMaterial {
    private FakeMaterial() {}

    /** Stands in for Material.Baked. */
    public static final class Baked {
        private final FakeSprite sprite;

        public Baked(FakeSprite sprite, boolean forceTranslucent) {
            this.sprite = sprite;
        }

        public FakeSprite sprite() {
            return sprite;
        }
    }
}
