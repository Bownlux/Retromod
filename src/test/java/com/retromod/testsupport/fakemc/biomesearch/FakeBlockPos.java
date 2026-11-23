/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.biomesearch;

/** Stands in for {@code BlockPos}. */
public final class FakeBlockPos {
    private final int x;
    private final int y;
    private final int z;

    public FakeBlockPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}
