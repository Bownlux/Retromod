/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Stands in for BakedQuad, named so a test can tell quads apart. */
public final class FakeBakedQuad {
    public final String name;
    private final int flags;

    public FakeBakedQuad(String name) {
        this(name, 0);
    }

    public FakeBakedQuad(String name, int flags) {
        this.name = name;
        this.flags = flags;
    }

    public MaterialInfo materialInfo() {
        return new MaterialInfo(flags);
    }

    /** Stands in for BakedQuad.MaterialInfo: only the material flags. */
    public static final class MaterialInfo {
        private final int flags;

        MaterialInfo(int flags) {
            this.flags = flags;
        }

        public int flags() {
            return flags;
        }
    }
}
