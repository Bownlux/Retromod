/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.mcstub;

/** Stands in for {@code net.minecraft.world.level.block.state.BlockBehaviour} in bridge tests. */
public class StubBlockBehaviour {

    public final Properties properties;

    public StubBlockBehaviour() {
        this(new Properties());
    }

    public StubBlockBehaviour(Properties properties) {
        this.properties = properties;
    }

    /** A block like the 1.20.5 barrier, whose map color reads a state property it declares. */
    public static StubBlockBehaviour waterloggedColored() {
        Properties properties = new Properties();
        properties.mapColor = state -> {
            if (state == null) throw new IllegalArgumentException("Cannot get property waterlogged");
            return StubMapColor.STONE;
        };
        return new StubBlockBehaviour(properties);
    }

    /** Stands in for {@code BlockBehaviour$Properties}. */
    public static class Properties {
        public StubResourceKey id;
        // The host's field has this name and type; the map color guard looks it up by both.
        java.util.function.Function<Object, Object> mapColor = state -> StubMapColor.NONE;

        public static Properties of() {
            return new Properties();
        }

        public static Properties ofFullCopy(StubBlockBehaviour block) {
            Properties copy = new Properties();
            copy.mapColor = block.properties.mapColor;
            return copy;
        }

        public Object mapColorOf(Object state) {
            return mapColor.apply(state);
        }

        public Properties setId(StubResourceKey id) {
            this.id = id;
            return this;
        }
    }
}
