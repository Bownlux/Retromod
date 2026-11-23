/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.server.players;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.players.CachedUserNameToIdResolver;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** The old profile cache's calls, answered by the name and id cache that replaced it in 1.21.9. */
public final class LegacyProfileCache {

    private LegacyProfileCache() {}

    /** {@code server.getProfileCache()}: null where the server's cache is not the cached kind, as the old getter could be. */
    public static CachedUserNameToIdResolver getProfileCache(Object server) {
        return cached(((MinecraftServer) server).services().nameToIdCache());
    }

    /** {@code services.profileCache()}. */
    public static CachedUserNameToIdResolver profileCache(Object services) {
        return cached(((Services) services).nameToIdCache());
    }

    /** {@code cache.get(name)}, which answered with a game profile. */
    public static Optional<GameProfile> get(Object cache, String name) {
        return ((UserNameToIdResolver) cache).get(name).map(LegacyProfileCache::toProfile);
    }

    /** {@code cache.get(id)}. */
    public static Optional<GameProfile> get(Object cache, UUID id) {
        return ((UserNameToIdResolver) cache).get(id).map(LegacyProfileCache::toProfile);
    }

    /** {@code cache.add(profile)}: the cache keeps only the name and id now. */
    public static void add(Object cache, GameProfile profile) {
        ((UserNameToIdResolver) cache).add(new NameAndId(profile));
    }

    /** {@code cache.getAsync(name)}: the lookup can reach Mojang's servers, so it runs off the calling thread. */
    public static CompletableFuture<Optional<GameProfile>> getAsync(Object cache, String name) {
        return CompletableFuture.supplyAsync(() -> get(cache, name));
    }

    /** {@code cache.setExecutor(executor)}: the cache has no executor now. */
    public static void setExecutor(Object cache, Executor executor) {
    }

    /** {@code cache.clearExecutor()}. */
    public static void clearExecutor(Object cache) {
    }

    private static CachedUserNameToIdResolver cached(UserNameToIdResolver cache) {
        return cache instanceof CachedUserNameToIdResolver cached ? cached : null;
    }

    // A profile with no properties, as the old cache stored.
    private static GameProfile toProfile(NameAndId entry) {
        return new GameProfile(entry.id(), entry.name());
    }
}
