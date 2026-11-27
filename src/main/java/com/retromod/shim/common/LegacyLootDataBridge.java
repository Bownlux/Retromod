/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;

/** Keeps a Mojang-named mod's loot table lookups by id working after 1.20.5 removed {@code LootDataManager}. */
public final class LegacyLootDataBridge {

    static final String DATA = RebuiltApiClasses.ROOT + "minecraft/world/level/storage/loot/LegacyLootData";
    static final String OLD_MANAGER = "net/minecraft/world/level/storage/loot/LootDataManager";
    static final String SERVER = "net/minecraft/server/MinecraftServer";
    // The trigger's package on 1.21.11, on 26.1, and from 26.2.
    static final String[] TRIGGERS = {"net/minecraft/advancements/critereon/LootTableTrigger",
            "net/minecraft/advancements/criterion/LootTableTrigger", "net/minecraft/advancements/triggers/LootTableTrigger"};
    static final String PLAYER = "net/minecraft/server/level/ServerPlayer";
    static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    static final String OLD_ID = "net/minecraft/resources/ResourceLocation";

    private LegacyLootDataBridge() {}

    /** Registers the stand-in where the host has no {@code LootDataManager}, which 1.20.5 removed; offline, from 1.20.5. */
    public static void register(RetromodTransformer t) {
        boolean running = ClassResourceInspector.read(SERVER) != null;
        String target = RetromodVersion.TARGET_MC_VERSION;
        boolean needed = running
                ? ClassResourceInspector.read(OLD_MANAGER) == null
                : RetromodVersion.compareMcVersions(target, "1.20.5") >= 0;
        if (!needed) return;
        // A host before 1.21.11 names ids ResourceLocation, so the stand-in is renamed to it there.
        boolean identifier = running
                ? ClassResourceInspector.read(IDENTIFIER) != null
                : RetromodVersion.compareMcVersions(target, "1.21.11") >= 0;
        registerUnchecked(t, identifier ? null : new SimpleRemapper(IDENTIFIER, OLD_ID));
    }

    /** Unconditional registration for transform-shape tests, on a host that names ids {@code Identifier}. */
    static void registerUnchecked(RetromodTransformer t) {
        registerUnchecked(t, null);
    }

    /** The stand-in and its redirects, with {@code names} renaming the stand-in's id class where the host has the old one. */
    static void registerUnchecked(RetromodTransformer t, Remapper names) {
        RebuiltApiClasses.registerTree(t, DATA, names);
        String hostId = names == null ? IDENTIFIER : OLD_ID;
        t.registerClassRedirect(OLD_MANAGER, DATA);
        String data = "L" + DATA + ";";
        // The descriptors as the mod wrote them and as they read once the class redirects ran.
        for (String manager : new String[]{OLD_MANAGER, DATA}) {
            t.registerMethodRedirect(SERVER, "getLootData", "()L" + manager + ";", DATA, "of", "(L" + SERVER + ";)" + data);
        }
        // The trigger now takes the table's key; the stand-in's own getLootTable needs no redirect.
        for (String trigger : TRIGGERS) {
            for (String id : new String[]{OLD_ID, IDENTIFIER}) {
                t.registerMethodRedirect(trigger, "trigger", "(L" + PLAYER + ";L" + id + ";)V",
                        DATA, "trigger", "(Ljava/lang/Object;L" + PLAYER + ";L" + hostId + ";)V", true);
            }
        }
    }
}
