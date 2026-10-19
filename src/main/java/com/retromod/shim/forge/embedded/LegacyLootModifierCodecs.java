/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Forge 1.20.1 registered a global loot modifier's serializer as a {@code Codec}. NeoForge 1.20.5
 * and later read that registry as {@code MapCodec}s: loading the loot modifier data casts each
 * entry, so one old serializer stops every modifier from loading. A serializer registered to that
 * registry is converted when it is built. A record codec is unwrapped to its map codec; any other
 * codec is treated as a map codec, which is what a loot modifier's codec always described.
 *
 * <p>Reflective, because this class is embedded into each mod and compiled without Minecraft.
 */
public final class LegacyLootModifierCodecs {

    private static final String REGISTRY_SUFFIX = "/ neoforge:global_loot_modifier_serializers]";
    private static final String MAP_CODEC = "com.mojang.serialization.MapCodec";

    private LegacyLootModifierCodecs() {}

    /** The supplier to register: converting for the loot modifier registry, unchanged otherwise. */
    public static Supplier<?> serializer(Object registryKey, Supplier<?> supplier) {
        // ResourceKey prints as ResourceKey[<registry> / <location>] on every version.
        if (!String.valueOf(registryKey).endsWith(REGISTRY_SUFFIX)) return supplier;
        return () -> toMapCodec(supplier.get());
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
            throw new IllegalStateException("Retromod could not convert the loot modifier serializer " + codec, e);
        }
    }
}
