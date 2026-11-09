/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.core;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

/** The registries of the save or load running on this thread, for old NBT code that is no longer passed them. */
public final class LegacyRegistryContext {

    // Every translated jar's copy shares one slot, so a save that runs another mod's old code passes its registries on.
    static final String SLOT = "registry-context";
    static final int PROTOCOL = 1;

    @SuppressWarnings("unchecked")
    private static final ThreadLocal<Object> CURRENT =
            LegacySharedSlots.get(SLOT, PROTOCOL, ThreadLocal.class, ThreadLocal::new);

    private LegacyRegistryContext() {}

    /** Makes {@code registries} current on this thread and returns what they replace, for {@link #exit}. */
    public static Object enter(HolderLookup.Provider registries) {
        Object previous = CURRENT.get();
        CURRENT.set(registries);
        return previous;
    }

    /** Puts back what {@link #enter} returned, so a save nested in another one leaves the outer registries current. */
    public static void exit(Object previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /** The registries of the save or load running on this thread, or null outside one. */
    public static HolderLookup.Provider current() {
        Object current = CURRENT.get();
        return current instanceof HolderLookup.Provider registries ? registries : null;
    }

    /** The current registries, else the running server's, else the client world's, for the old call {@code call}. */
    public static HolderLookup.Provider provider(String call) {
        HolderLookup.Provider registries = available();
        if (registries != null) return registries;
        // Built-in registries cannot encode data-driven content such as enchantments, so Retromod does not guess.
        throw new IllegalStateException("Retromod found no registries for the old call " + call
                + ": no save or load is running on this thread, no server is running, and no world is loaded");
    }

    /** The current registries, else the running server's, else the client world's, or null when there are none. */
    public static HolderLookup.Provider available() {
        HolderLookup.Provider current = current();
        if (current != null) return current;
        HolderLookup.Provider registries = ServerRegistries.find();
        if (registries == null) registries = NeoForgeServerRegistries.find();
        return registries != null ? registries : ClientRegistries.find();
    }

    // Forge's server hooks, in a class of their own so another loader never links them.
    private static final class ServerRegistries {
        static HolderLookup.Provider find() {
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                return server == null ? null : server.registryAccess();
            } catch (LinkageError notForge) {
                return null;
            }
        }
    }

    // NeoForge's server hooks, in a class of their own so Forge never links them.
    private static final class NeoForgeServerRegistries {
        static HolderLookup.Provider find() {
            try {
                MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                return server == null ? null : server.registryAccess();
            } catch (LinkageError notNeoForge) {
                return null;
            }
        }
    }

    // The client world, in a class of its own: a dedicated server has no client classes.
    private static final class ClientRegistries {
        static HolderLookup.Provider find() {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                ClientLevel level = minecraft == null ? null : minecraft.level;
                return level == null ? null : level.registryAccess();
            } catch (LinkageError | RuntimeException noClient) {
                return null;
            }
        }
    }
}
