/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen;

import com.mojang.serialization.MapCodec;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.SurfaceRules;

/** The surface rule members 26.2 reshaped: a biome condition built from keys, and the context's biome supplier. */
public final class LegacySurfaceRules {

    // Forge and NeoForge name the running server here; Fabric's game instance is asked after them.
    private static final String[] SERVER_HOOKS = {
            "net.minecraftforge.server.ServerLifecycleHooks", "net.neoforged.neoforge.server.ServerLifecycleHooks"};

    private static volatile Field possibleBiomes;
    private static volatile Method biomeGetter;
    private static volatile ServerSource serverSource;

    private LegacySurfaceRules() {}

    /** The old {@code isBiome(keys)}: a condition that finds its biomes when the world generates, with no server needed. */
    public static SurfaceRules.ConditionSource isBiome(ResourceKey<?>... keys) {
        return new KeyBiomeCondition(keys.clone());
    }

    /** The old {@code Context.biome} supplier, which asks the context for the biome where it stands when read. */
    public static Supplier<Object> biome(Object context) {
        return () -> currentBiome(context);
    }

    /** A biome condition kept as keys, turned into 26.2's own condition for the biomes of the world it is applied in. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class KeyBiomeCondition implements SurfaceRules.ConditionSource {

        /** The host condition for one server's biome registry. */
        private record ForRegistry(Registry registry, SurfaceRules.ConditionSource source) {}

        private final ResourceKey<?>[] keys;
        // The holder each key last matched in a surface step, for a carver when no server can be found.
        private final Map<ResourceKey, Holder.Reference> latest = new ConcurrentHashMap<>();
        private volatile ForRegistry forRegistry;

        KeyBiomeCondition(ResourceKey<?>[] keys) {
            this.keys = keys;
        }

        @Override
        public Object apply(Object context) {
            Set<?> biomes = possibleBiomes(context);
            if (biomes != null) {
                // A surface step names the biomes of its own chunk, so each call brings a new set.
                Map<ResourceKey, Holder.Reference> found = matching(biomes);
                latest.putAll(found);
                return hostCondition(found).apply(context);
            }
            // A carver names no biome set, and its context has not moved yet, so its biome cannot be read here.
            return carverCondition().apply(context);
        }

        /** The condition for a carver's context, from the running server's biome registry when one is found. */
        private SurfaceRules.ConditionSource carverCondition() {
            Registry registry = serverBiomes();
            if (registry == null) return hostCondition(new HashMap<>(latest));
            ForRegistry current = forRegistry;
            if (current == null || current.registry() != registry) {
                // A new world, or a reload, brings a new registry with new holders.
                current = new ForRegistry(registry, hostCondition(registered(registry)));
                forRegistry = current;
            }
            return current.source();
        }

        /** The holders in {@code biomes} that match a key. */
        private Map<ResourceKey, Holder.Reference> matching(Set<?> biomes) {
            Map<ResourceKey, Holder.Reference> found = new HashMap<>();
            for (Object biome : biomes) {
                for (ResourceKey key : keys) {
                    if (biome instanceof Holder.Reference holder && holder.is(key)) found.put(key, holder);
                }
            }
            return found;
        }

        /** The holders {@code registry} has for the keys. */
        private Map<ResourceKey, Holder.Reference> registered(Registry registry) {
            Map<ResourceKey, Holder.Reference> found = new HashMap<>();
            for (ResourceKey key : keys) {
                Optional<Holder.Reference> holder = registry.get(key);
                holder.ifPresent(reference -> found.put(key, reference));
            }
            return found;
        }

        /** The host's own biome condition over {@code found}. */
        private SurfaceRules.ConditionSource hostCondition(Map<ResourceKey, Holder.Reference> found) {
            // A key the world does not have can never match there, as an unknown key never did before.
            List<ResourceKey> present = new ArrayList<>(found.keySet());
            return SurfaceRules.isBiome(new FoundBiomes(found), present.toArray(new ResourceKey[0]));
        }

        /** Not written back to data: the condition holds only keys and needs a world to mean anything. */
        @Override
        public MapCodec codec() {
            return MapCodec.unit(this);
        }
    }

    /** A biome lookup that answers only the holders a condition found in its world. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class FoundBiomes implements HolderGetter {
        private final Map<ResourceKey, Holder.Reference> found;

        FoundBiomes(Map<ResourceKey, Holder.Reference> found) {
            this.found = found;
        }

        @Override
        public Optional<Holder.Reference> get(ResourceKey key) {
            return Optional.ofNullable(found.get(key));
        }

        @Override
        public Optional<HolderSet.Named> get(TagKey tag) {
            return Optional.empty();
        }
    }

    /** The running server's biome registry, or null when the loader names no server. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Registry serverBiomes() {
        ServerSource source = serverSource;
        if (source == null) {
            source = ServerSource.find();
            serverSource = source;
        }
        try {
            if (!(source.server() instanceof MinecraftServer server)) return null;
            return (Registry) server.registryAccess().lookup((ResourceKey) Registries.BIOME).orElse(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Retromod could not ask the loader for the running server's biomes", e);
        }
    }

    /** How this loader names the running server: a getter on {@code target}, then a client's integrated server. */
    private record ServerSource(Method getter, Object target, Method integrated) {

        static ServerSource find() {
            for (String hooks : SERVER_HOOKS) {
                Method current = publicMethod(hooks, "getCurrentServer");
                if (current != null) return new ServerSource(current, null, null);
            }
            // Fabric hands out the game: the server itself, or a client that hosts its world in an integrated server.
            Method instance = publicMethod("net.fabricmc.loader.api.FabricLoader", "getInstance");
            Method game = publicMethod("net.fabricmc.loader.api.FabricLoader", "getGameInstance");
            if (instance == null || game == null) return new ServerSource(null, null, null);
            try {
                return new ServerSource(game, instance.invoke(null),
                        publicMethod("net.minecraft.client.Minecraft", "getSingleplayerServer"));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Retromod could not reach Fabric Loader to find the running server", e);
            }
        }

        Object server() throws ReflectiveOperationException {
            Object game = getter == null ? null : getter.invoke(target);
            if (game == null || game instanceof MinecraftServer) return game;
            return integrated != null && integrated.getDeclaringClass().isInstance(game) ? integrated.invoke(game) : null;
        }

        private static Method publicMethod(String owner, String name) {
            try {
                return Class.forName(owner, false, LegacySurfaceRules.class.getClassLoader()).getMethod(name);
            } catch (ReflectiveOperationException | LinkageError absent) {
                return null;
            }
        }
    }

    /** The biome set 26.2's generator passes each surface context, or null for a carver's context. */
    private static Set<?> possibleBiomes(Object context) {
        try {
            Field field = possibleBiomes;
            if (field == null) {
                field = context.getClass().getDeclaredField("possibleBiomes");
                field.setAccessible(true);
                possibleBiomes = field;
            }
            return (Set<?>) field.get(context);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Retromod could not read the surface rule context's biomes on this host", e);
        }
    }

    private static Object currentBiome(Object context) {
        try {
            Method getter = biomeGetter;
            if (getter == null) {
                // getBiome() is protected unless the mod's access transformer opened it, as TerraBlender's does.
                getter = context.getClass().getDeclaredMethod("getBiome");
                getter.setAccessible(true);
                biomeGetter = getter;
            }
            return getter.invoke(context);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Retromod could not read the surface rule context's biome on this host", e);
        }
    }
}
