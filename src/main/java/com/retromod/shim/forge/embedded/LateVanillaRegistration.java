/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** A Forge mod's direct {@code Registry.register} into a vanilla registry, on NeoForge and Forge 26.x, which freeze them earlier. */
public final class LateVanillaRegistration {

    private LateVanillaRegistration() {}

    /** {@code Registry.register(registry, id, value)} for a String, ResourceLocation or ResourceKey id. */
    public static Object register(Object registry, Object id, Object value) throws Throwable {
        return call("register", registry, id, value);
    }

    /** {@code Registry.registerForHolder(registry, id, value)}. */
    public static Object registerForHolder(Object registry, Object id, Object value) throws Throwable {
        return call("registerForHolder", registry, id, value);
    }

    private static Object call(String name, Object registry, Object id, Object value) throws Throwable {
        Method method = registryMethod(registry, name, id);
        value = asRegistryValue(registry, value);
        try {
            return method.invoke(null, registry, id, value);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (!frozen(cause) || !stillLoading(registry)) throw cause;
            // Forge kept the vanilla registries writable until loading finished, and mods registered
            // from setup tasks and static initializers. NeoForge freezes them after the registration
            // events, so they reopen around the call, and freezing again snapshots the new entry.
            Class<?> gameData = loaderClass(registry, "registries.GameData");
            gameData.getMethod("unfreezeData").invoke(null);
            try {
                return method.invoke(null, registry, id, value);
            } catch (InvocationTargetException again) {
                throw again.getCause();
            } finally {
                gameData.getMethod("freezeData").invoke(null);
            }
        }
    }

    /**
     * 1.20.5 made the chunk generator, biome source and other worldgen registries hold map codecs,
     * and an older mod registers a plain codec there. The registry's own entries tell which kind it
     * holds; a record codec unwraps to its map codec, and any other codec is used as one.
     */
    static Object asRegistryValue(Object registry, Object value) {
        try {
            ClassLoader loader = registry.getClass().getClassLoader();
            Class<?> codec = Class.forName("com.mojang.serialization.Codec", false, loader);
            Class<?> mapCodec = Class.forName("com.mojang.serialization.MapCodec", false, loader);
            if (!codec.isInstance(value) || mapCodec.isInstance(value)) return value;
            Class<?> api = Class.forName("net.minecraft.core.Registry", false, loader);
            Object sample = ((java.util.stream.Stream<?>) api.getMethod("stream").invoke(registry)).findFirst().orElse(null);
            if (!mapCodec.isInstance(sample)) return value;
            Class<?> recordCodec = Class.forName("com.mojang.serialization.MapCodec$MapCodecCodec", false, loader);
            if (recordCodec.isInstance(value)) return recordCodec.getMethod("codec").invoke(value);
            return mapCodec.getMethod("assumeMapUnsafe", codec).invoke(null, value);
        } catch (ReflectiveOperationException | LinkageError e) {
            return value;
        }
    }

    /** The static {@code Registry} method whose id parameter takes this id. */
    private static Method registryMethod(Object registry, String name, Object id) throws ReflectiveOperationException {
        Class<?> api = Class.forName("net.minecraft.core.Registry", false, registry.getClass().getClassLoader());
        for (Method method : api.getMethods()) {
            if (method.getName().equals(name) && Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 3 && method.getParameterTypes()[1].isInstance(id)) {
                return method;
            }
        }
        throw new NoSuchMethodException("Registry." + name + " for " + id.getClass().getName());
    }

    /** NeoForge's class of this name, or Forge's on Forge 26.x, which keeps the same freeze switches. */
    private static Class<?> loaderClass(Object registry, String name) throws ClassNotFoundException {
        ClassLoader loader = registry.getClass().getClassLoader();
        try {
            return Class.forName("net.neoforged.neoforge." + name, true, loader);
        } catch (ClassNotFoundException notNeoForge) {
            return Class.forName("net.minecraftforge." + name, true, loader);
        }
    }

    private static boolean frozen(Throwable cause) {
        return cause instanceof IllegalStateException && String.valueOf(cause.getMessage()).contains("frozen");
    }

    /** Ids are final once a server runs, and may already be synced to clients. */
    private static boolean stillLoading(Object registry) {
        try {
            Object server = loaderClass(registry, "server.ServerLifecycleHooks").getMethod("getCurrentServer").invoke(null);
            return server == null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }
}
