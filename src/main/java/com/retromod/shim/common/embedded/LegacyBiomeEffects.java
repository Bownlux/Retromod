/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Runtime half of {@link com.retromod.shim.common.LegacyBiomeEffectsBridge}: keeps the biome
 * effects 1.21.11 moved out of {@code BiomeSpecialEffects} and sets them as the biome's
 * environment attributes. Reflective because this class is embedded into each mod and compiled
 * without Minecraft; its names are Mojang's on every host the bridge runs on.
 *
 * <p>An old mod set fog, sky, and water fog colors, particles, sounds, and music on the effects
 * builder. They are held for that builder, follow it into the effects it builds, and are set on
 * each biome builder those effects are given to.
 */
public final class LegacyBiomeEffects {
    private LegacyBiomeEffects() {}

    // The pieces of the old ambient sound settings, kept until they become one AmbientSounds value.
    // A single class, because only this class is embedded into the mod.
    private static final String LOOP = "loop";
    private static final String MOOD = "mood";
    private static final String ADDITIONS = "additions";

    /**
     * Pending values by effects builder, keyed by {@code EnvironmentAttributes} field name or one of
     * the ambient sound parts. A builder does not override equals, so this is by identity.
     */
    private static final Map<Object, Map<String, Object>> BY_BUILDER = Collections.synchronizedMap(new WeakHashMap<>());
    /** By built effects, which are records and must not merge: two biomes can share colors but not fog. */
    private static final Map<Object, Map<String, Object>> BY_EFFECTS = Collections.synchronizedMap(new IdentityHashMap<>());

    public static Object fogColor(Object builder, int color) {
        return set(builder, "FOG_COLOR", color);
    }

    public static Object waterFogColor(Object builder, int color) {
        return set(builder, "WATER_FOG_COLOR", color);
    }

    public static Object skyColor(Object builder, int color) {
        return set(builder, "SKY_COLOR", color);
    }

    public static Object ambientParticle(Object builder, Object particle) {
        return set(builder, "AMBIENT_PARTICLES", List.of(particle));
    }

    public static Object ambientLoopSound(Object builder, Object sound) {
        return set(builder, LOOP, sound);
    }

    public static Object ambientMoodSound(Object builder, Object mood) {
        return set(builder, MOOD, mood);
    }

    public static Object ambientAdditionsSound(Object builder, Object additions) {
        return set(builder, ADDITIONS, additions);
    }

    public static Object backgroundMusic(Object builder, Object music) {
        return set(builder, "BACKGROUND_MUSIC", music);
    }

    /** {@code BiomeSpecialEffects.Builder.build()}: the effects carry the builder's pending attributes. */
    public static Object build(Object builder) {
        Object effects = invoke(builder, "build");
        Map<String, Object> pending = BY_BUILDER.remove(builder);
        if (pending != null) BY_EFFECTS.put(effects, pending);
        return effects;
    }

    /** {@code Biome.BiomeBuilder.specialEffects(effects)}: also sets the attributes those effects carry. */
    public static Object specialEffects(Object biomeBuilder, Object effects) {
        Map<String, Object> pending = BY_EFFECTS.get(effects);
        if (pending != null) {
            ClassLoader loader = biomeBuilder.getClass().getClassLoader();
            for (Map.Entry<String, Object> attribute : pending.entrySet()) {
                if (!List.of(LOOP, MOOD, ADDITIONS).contains(attribute.getKey())) {
                    setAttribute(biomeBuilder, loader, attribute.getKey(), attribute.getValue());
                }
            }
            if (pending.containsKey(LOOP) || pending.containsKey(MOOD) || pending.containsKey(ADDITIONS)) {
                setAttribute(biomeBuilder, loader, "AMBIENT_SOUNDS", ambientSounds(loader, pending));
            }
        }
        for (Method method : biomeBuilder.getClass().getMethods()) {
            if (method.getName().equals("specialEffects") && method.getParameterCount() == 1) {
                return call(method, biomeBuilder, effects);
            }
        }
        throw new IllegalStateException("Biome.BiomeBuilder has no specialEffects method on this Minecraft version");
    }

    private static Object set(Object builder, String key, Object value) {
        Map<String, Object> pending = BY_BUILDER.computeIfAbsent(builder, b -> new LinkedHashMap<>());
        pending.put(key, value);
        return builder;
    }

    private static void setAttribute(Object biomeBuilder, ClassLoader loader, String name, Object value) {
        try {
            Field field = Class.forName("net.minecraft.world.attribute.EnvironmentAttributes", true, loader).getField(name);
            Object converted = value == null ? null : convert(loader, field, value);
            if (converted == null) return;
            Class<?> attributeType = Class.forName("net.minecraft.world.attribute.EnvironmentAttribute", true, loader);
            biomeBuilder.getClass().getMethod("setAttribute", attributeType, Object.class)
                    .invoke(biomeBuilder, field.get(null), converted);
        } catch (ReflectiveOperationException | LinkageError e) {
            System.out.println("[Retromod] Legacy biome effects: could not set " + name.toLowerCase()
                    + " on a biome, so it keeps the default: " + e);
        }
    }

    /**
     * The value in the attribute's type. 26.3 stores colors as vectors where 26.1 and 26.2 store
     * the packed RGB integer, and a music attribute wraps the old {@code Music}.
     */
    private static Object convert(ClassLoader loader, Field attribute, Object value) throws ReflectiveOperationException {
        Class<?> valueType = valueType(attribute);
        if (value instanceof Integer color && valueType != null && valueType != Integer.class) {
            return Class.forName("net.minecraft.util.ARGB", true, loader).getMethod("vector3fFromRGB24", int.class)
                    .invoke(null, color);
        }
        if (attribute.getName().equals("BACKGROUND_MUSIC")) {
            Class<?> musicType = Class.forName("net.minecraft.sounds.Music", true, loader);
            Object music = musicType.isInstance(value) ? value : firstWeighted(value);
            if (!musicType.isInstance(music)) return null;
            return Class.forName("net.minecraft.world.attribute.BackgroundMusic", true, loader)
                    .getConstructor(musicType).newInstance(music);
        }
        return value;
    }

    /** 1.21.5 made biome music a weighted list; 26.x plays one track, so the first one is kept. */
    private static Object firstWeighted(Object list) {
        try {
            List<?> entries = (List<?>) list.getClass().getMethod("unwrap").invoke(list);
            if (entries.isEmpty()) return null;
            Object first = entries.get(0);
            return first.getClass().getMethod("value").invoke(first);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    private static Class<?> valueType(Field attribute) {
        if (attribute.getGenericType() instanceof ParameterizedType type
                && type.getActualTypeArguments()[0] instanceof Class<?> value) {
            return value;
        }
        return null;
    }

    private static Object ambientSounds(ClassLoader loader, Map<String, Object> pending) {
        try {
            return Class.forName("net.minecraft.world.attribute.AmbientSounds", true, loader)
                    .getConstructor(Optional.class, Optional.class, List.class)
                    .newInstance(Optional.ofNullable(pending.get(LOOP)), Optional.ofNullable(pending.get(MOOD)),
                            pending.containsKey(ADDITIONS) ? List.of(pending.get(ADDITIONS)) : List.of());
        } catch (ReflectiveOperationException | LinkageError e) {
            System.out.println("[Retromod] Legacy biome effects: could not build a biome's ambient sounds,"
                    + " so it keeps the default: " + e);
            return null;
        }
    }

    private static Object invoke(Object target, String name) {
        try {
            return call(target.getClass().getMethod(name), target);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(target.getClass().getName() + " has no " + name + " method", e);
        }
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
