/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Moves an old mod's carver classes onto an inert carver type, since 26.3 removed the carving engine they extended. */
public final class LegacyCarverBridge {

    static final String CARVERS = "net/minecraft/world/level/levelgen/carver/";
    static final String WORLD_CARVER = CARVERS + "WorldCarver";
    static final List<String> OLD_BASES = List.of(WORLD_CARVER, CARVERS + "CaveWorldCarver", CARVERS + "CanyonWorldCarver",
            CARVERS + "NetherWorldCarver");
    static final List<String> OLD_CONFIGS = List.of(CARVERS + "CarverConfiguration", CARVERS + "CaveCarverConfiguration",
            CARVERS + "CanyonCarverConfiguration");
    static final String BASE = RebuiltApiClasses.ROOT + "minecraft/world/level/levelgen/carver/LegacyCarverType";
    static final String CONFIG = RebuiltApiClasses.ROOT + "minecraft/world/level/levelgen/carver/LegacyCarverConfiguration";

    private LegacyCarverBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostRebuiltIt(host.apply(WORLD_CARVER))) return;
        RebuiltApiClasses.registerTree(t, BASE);
        // WorldCarver became an interface and the others final records, so only an old class can extend them.
        for (String old : OLD_BASES) t.registerSuperclassRebase(old, BASE);
        // The deleted configurations become one stand-in whose CODEC accepts any settings, so CODEC reads still link.
        RebuiltApiClasses.registerTree(t, CONFIG);
        for (String old : OLD_CONFIGS) t.registerClassRedirect(old, CONFIG);
    }

    /** Whether the host's WorldCarver is the 26.3 interface; offline or unreadable, a target from 26.3. */
    static boolean hostRebuiltIt(ClassNode carver) {
        if (carver == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        return (carver.access & Opcodes.ACC_INTERFACE) != 0;
    }
}
