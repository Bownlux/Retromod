/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Runtime half of {@link com.retromod.shim.common.LegacyFeatureBridge}. Reflective because this
 * class is embedded into each mod and compiled without Minecraft or DataFixerUpper; DataFixerUpper's
 * names never change, and Minecraft's are Mojang's on every 26.3 host.
 */
public final class LegacyFeatureSupport {
    private LegacyFeatureSupport() {}

    /**
     * The codec an old feature type answers with: the mod's own configuration codec, read from
     * the flattened 26.3 feature JSON, building one configured feature per data entry.
     */
    public static Object delegateCodec(Object feature, Object configCodec, Class<?> configured) {
        try {
            ClassLoader loader = configCodec.getClass().getClassLoader();
            Class<?> mapCodec = Class.forName("com.mojang.serialization.MapCodec", true, loader);
            Class<?> codec = Class.forName("com.mojang.serialization.Codec", true, loader);
            Object map = mapCodec.getMethod("assumeMapUnsafe", codec).invoke(null, configCodec);
            Constructor<?> make = configured.getConstructors()[0];
            Field config = configured.getField("config");
            Function<Object, Object> decode = value -> {
                try {
                    return make.newInstance(feature, value);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            };
            Function<Object, Object> encode = placed -> {
                try {
                    return config.get(placed);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            };
            return mapCodec.getMethod("xmap", Function.class, Function.class).invoke(map, decode, encode);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build the codec for " + feature.getClass().getName(), e);
        }
    }

    /** {@code Codec} that always decodes to {@code value}, for {@code NoneFeatureConfiguration.CODEC}. */
    public static Object unitCodec(Object value) {
        try {
            Class<?> mapCodec = Class.forName("com.mojang.serialization.MapCodec", true,
                    LegacyFeatureSupport.class.getClassLoader());
            Object unit = mapCodec.getMethod("unit", Object.class).invoke(null, value);
            return mapCodec.getMethod("codec").invoke(unit);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build the empty feature configuration codec", e);
        }
    }

    /** {@code Feature.isReplaceable(tag)}: blocks not in {@code tag}. */
    public static Predicate<Object> isReplaceable(Object tag) {
        return state -> {
            try {
                for (Method method : state.getClass().getMethods()) {
                    if (method.getName().equals("is") && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].isInstance(tag)) {
                        return !(Boolean) method.invoke(state, tag);
                    }
                }
            } catch (ReflectiveOperationException ignored) {
                // Treat an unreadable state as not replaceable.
            }
            return false;
        };
    }

    /** {@code Feature.checkNeighbors(lookup, pos, predicate)}: whether any of the six neighbors matches. */
    @SuppressWarnings("unchecked")
    public static boolean checkNeighbors(Function<Object, Object> lookup, Object pos, Predicate<Object> predicate) {
        try {
            Class<?> direction = Class.forName("net.minecraft.core.Direction", true, pos.getClass().getClassLoader());
            Method relative = pos.getClass().getMethod("relative", direction);
            for (Object side : direction.getEnumConstants()) {
                if (predicate.test(lookup.apply(relative.invoke(pos, side)))) return true;
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not read a feature's neighbors", e);
        }
        return false;
    }

    /** {@code Feature.isAdjacentToAir(lookup, pos)}. */
    public static boolean isAdjacentToAir(Function<Object, Object> lookup, Object pos) {
        return checkNeighbors(lookup, pos, state -> {
            try {
                return (Boolean) state.getClass().getMethod("isAir").invoke(state);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        });
    }
}
