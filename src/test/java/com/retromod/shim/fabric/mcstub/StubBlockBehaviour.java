/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.mcstub;

/** Stands in for {@code net.minecraft.world.level.block.state.BlockBehaviour} in bridge tests. */
public class StubBlockBehaviour {

    /** Stands in for {@code BlockBehaviour$Properties}. */
    public static class Properties {
        public StubResourceKey id;

        public static Properties of() {
            return new Properties();
        }

        public static Properties ofFullCopy(StubBlockBehaviour block) {
            return new Properties();
        }

        public Properties setId(StubResourceKey id) {
            this.id = id;
            return this;
        }
    }
}
