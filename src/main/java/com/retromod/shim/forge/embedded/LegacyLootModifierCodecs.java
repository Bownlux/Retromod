/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Forge 1.20.1 registered a global loot modifier's, biome modifier's, or structure modifier's
 * serializer as a {@code Codec}. NeoForge 1.20.5 and later read those registries as
 * {@code MapCodec}s: loading the data casts each entry, so one old serializer stops every modifier
 * from loading. A serializer registered to one of them is converted when it is built. A record
 * codec is unwrapped to its map codec; any other codec is treated as a map codec, which is what a
 * modifier's codec always described.
 *
 * <p>Reflective, because this class is embedded into each mod and compiled without Minecraft.
 */
public final class LegacyLootModifierCodecs {

    private static final String[] REGISTRY_SUFFIXES = {"/ neoforge:global_loot_modifier_serializers]",
            "/ neoforge:biome_modifier_serializers]", "/ neoforge:structure_modifier_serializers]"};
    private static final String MAP_CODEC = "com.mojang.serialization.MapCodec";

    private LegacyLootModifierCodecs() {}

    /** The supplier to register: converting for the loot modifier registry, unchanged otherwise. */
    public static Supplier<?> serializer(Object registryKey, Supplier<?> supplier) {
        // ResourceKey prints as ResourceKey[<registry> / <location>] on every version.
        String key = String.valueOf(registryKey);
        for (String suffix : REGISTRY_SUFFIXES) {
            if (key.endsWith(suffix)) return () -> toMapCodec(supplier.get());
        }
        return supplier;
    }

    static Object toMapCodec(Object codec) {
        if (codec == null) return null;
        try {
            Class<?> mapCodec = Class.forName(MAP_CODEC, false, codec.getClass().getClassLoader());
            if (mapCodec.isInstance(codec)) return codec;
            if (codec.getClass().getName().equals(MAP_CODEC + "$MapCodecCodec")) {
                return codec.getClass().getMethod("codec").invoke(codec);
            }
            Method assume = mapCodec.getMethod("assumeMapUnsafe", Class.forName(
                    "com.mojang.serialization.Codec", false, mapCodec.getClassLoader()));
            return assume.invoke(null, codec);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Retromod could not convert the modifier serializer " + codec, e);
        }
    }
}
