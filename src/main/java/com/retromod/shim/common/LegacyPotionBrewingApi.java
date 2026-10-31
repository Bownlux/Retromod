/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

import java.util.List;

/** Rebuilds {@code PotionBrewing}, which 26.3 replaced with brewing recipes, inside each translated jar that reads it. */
public final class LegacyPotionBrewingApi {

    static final String OLD = "net/minecraft/world/item/alchemy/PotionBrewing";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/world/item/alchemy/PotionBrewing";

    // Each server and level handed out the table, so every one of them is asked the same way.
    private static final List<String> SERVERS = List.of("net/minecraft/server/MinecraftServer",
            "net/minecraft/server/dedicated/DedicatedServer", "net/minecraft/client/server/IntegratedServer");
    private static final List<String> LEVELS = List.of("net/minecraft/world/level/Level",
            "net/minecraft/server/level/ServerLevel", "net/minecraft/client/multiplayer/ClientLevel");

    private LegacyPotionBrewingApi() {}

    /** Whether the host lost the class: read from the host when it can be, else from the target. */
    static boolean hostRemovedPotionBrewing() {
        if (ClassResourceInspector.exists("net/minecraft/world/item/alchemy/Potion")) {
            return !ClassResourceInspector.exists(OLD);
        }
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
    }

    public static void register(RetromodTransformer t) {
        if (hostRemovedPotionBrewing()) registerClasses(t);
    }

    static void registerClasses(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, REBUILT);
        t.registerClassRedirect(OLD, REBUILT);
        t.registerClassRedirect(OLD + "$Mix", REBUILT + "$Mix");
        // The builder is left alone: Fabric API's brewing event already records old mixes as recipes.
        String oldGetter = "()L" + OLD + ";";
        for (String server : SERVERS) {
            t.registerMethodRedirect(server, "potionBrewing", oldGetter,
                    REBUILT, "of", "(Lnet/minecraft/server/MinecraftServer;)L" + REBUILT + ";", true);
        }
        for (String level : LEVELS) {
            t.registerMethodRedirect(level, "potionBrewing", oldGetter,
                    REBUILT, "of", "(Lnet/minecraft/world/level/Level;)L" + REBUILT + ";", true);
        }
    }
}
