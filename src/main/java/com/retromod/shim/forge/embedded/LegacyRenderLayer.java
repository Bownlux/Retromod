/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stands in for 1.12.2's {@code BlockRenderLayer}, which a block returned from
 * {@code getRenderLayer()}. 1.13 moved the choice into client registration, so the constants only
 * let the block's code load; they do not change how the block renders.
 */
public final class LegacyRenderLayer {
    public static final LegacyRenderLayer SOLID = new LegacyRenderLayer("SOLID");
    public static final LegacyRenderLayer CUTOUT_MIPPED = new LegacyRenderLayer("CUTOUT_MIPPED");
    public static final LegacyRenderLayer CUTOUT = new LegacyRenderLayer("CUTOUT");
    public static final LegacyRenderLayer TRANSLUCENT = new LegacyRenderLayer("TRANSLUCENT");

    private final String name;

    private LegacyRenderLayer(String name) {
        this.name = name;
    }

    public String name() { return name; }

    @Override
    public String toString() { return name; }
}
