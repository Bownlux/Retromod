/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;

import java.util.List;

/** Rebuilds NeoForge 1.21.1's effect cures on NeoForge hosts that removed them. */
public final class LegacyEffectCureBridge {

    static final String NEO = "net/neoforged/neoforge/";
    static final String OLD_CURE = NEO + "common/EffectCure";
    static final String OLD_CURES = NEO + "common/EffectCures";
    static final String ROOT = RebuiltApiClasses.ROOT + "neoforge/common/";
    static final String CURE = ROOT + "LegacyEffectCure";
    static final String CURES = ROOT + "LegacyEffectCures";
    static final String CALLS = ROOT + "LegacyEffectCureCalls";
    /** NeoForge 1.21.11 has no cures; the older 1.21 hosts are read at run time. */
    static final String OFFLINE_FROM = "1.21.11";
    // The entity types old mods call removeEffectsCuredBy through.
    static final List<String> ENTITIES = List.of("net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/player/Player",
            "net/minecraft/server/level/ServerPlayer");

    private LegacyEffectCureBridge() {}

    public static void register(RetromodTransformer t) {
        if (!curesGone()) return;
        RebuiltApiClasses.registerTree(t, CALLS);
        t.registerClassRedirect(OLD_CURE, CURE);
        t.registerClassRedirect(OLD_CURES, CURES);
        // The helpers take the rebuilt cure, the type the class redirect gives the mod's own code.
        String cure = "L" + OLD_CURE + ";";
        String rebuilt = "L" + CURE + ";";
        for (String entity : ENTITIES) {
            t.registerMethodRedirect(entity, "removeEffectsCuredBy", "(" + cure + ")Z", CALLS, "removeEffectsCuredBy",
                    "(Lnet/minecraft/world/entity/LivingEntity;" + rebuilt + ")Z", true);
        }
        String instance = "net/minecraft/world/effect/MobEffectInstance";
        t.registerMethodRedirect(instance, "getCures", "()Ljava/util/Set;", CALLS, "getCures", "(L" + instance + ";)Ljava/util/Set;", true);
        // The removal event lost its cure, so the hook is called without it.
        String removed = "(Lnet/minecraft/world/entity/LivingEntity;L" + instance + ";";
        t.registerMethodRedirect(NEO + "event/EventHooks", "onEffectRemoved", removed + cure + ")Z", CALLS, "onEffectRemoved",
                removed + rebuilt + ")Z");
    }

    /** Whether the running NeoForge has no effect cures; offline, whether the target is at least {@link #OFFLINE_FROM}. */
    static boolean curesGone() {
        if (ClassResourceInspector.read(NEO + "common/NeoForge") == null) {
            return !RetromodVersion.mcVersionExceeds(OFFLINE_FROM, RetromodVersion.TARGET_MC_VERSION);
        }
        return ClassResourceInspector.read(OLD_CURE) == null;
    }
}
