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
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Rebuilds LevelTimeAccess's moon phase, moon brightness, time of day and day time, which 1.21.11 replaced with environment attributes. */
public final class LegacyLevelTimeBridge {

    public static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/world/level/LegacyLevelTime";
    static final String TIME_ACCESS = "net/minecraft/world/level/LevelTimeAccess";
    static final String LEVEL = "net/minecraft/world/level/Level";
    static final String ATTRIBUTES = "net/minecraft/world/attribute/EnvironmentAttributes";
    static final String FIRST_HOST = "1.21.11";
    static final String FIRST_CLOCK_HOST = "26.1";
    // Javac names the class a call is made on, so each level type that inherited the members from LevelTimeAccess is listed.
    static final List<String> OWNERS = List.of(TIME_ACCESS, "net/minecraft/world/level/LevelAccessor",
            "net/minecraft/world/level/ServerLevelAccessor", "net/minecraft/world/level/WorldGenLevel", LEVEL,
            "net/minecraft/client/multiplayer/ClientLevel", "net/minecraft/server/level/ServerLevel",
            "net/minecraft/server/level/WorldGenRegion");
    // Name, the old descriptor, and the helper's, which takes the level first.
    static final String[][] MEMBERS = {
            {"getMoonPhase", "()I", "(Ljava/lang/Object;)I"},
            {"getMoonBrightness", "()F", "(Ljava/lang/Object;)F"},
            {"getTimeOfDay", "(F)F", "(Ljava/lang/Object;F)F"},
            {"dayTime", "()J", "(Ljava/lang/Object;)J"}};

    private LegacyLevelTimeBridge() {}

    /** Mojang names: Forge and NeoForge from 1.21.11, and Fabric from 26.1. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostReplacedTimeAccess(host)) return;
        RebuiltApiClasses.registerTree(t, HELPER, dayTimeNames(host));
        for (String owner : OWNERS) {
            for (String[] member : MEMBERS) {
                // A method reference such as GeckoLib's level::getMoonPhase takes the same redirect as a call.
                t.registerMethodRedirect(owner, member[0], member[1], HELPER, member[0], member[2], true);
            }
        }
    }

    /** Whether the host has the environment attributes and no LevelTimeAccess; offline the target decides. */
    static boolean hostReplacedTimeAccess(Function<String, ClassNode> host) {
        ClassNode attributes = host.apply(ATTRIBUTES);
        if (attributes != null || host.apply(LEVEL) != null) return attributes != null && host.apply(TIME_ACCESS) == null;
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) >= 0;
    }

    /** The helper reads the 26.x overworld clock; a 1.21.11 host still names the same value getDayTime. */
    static Remapper dayTimeNames(Function<String, ClassNode> host) {
        ClassNode level = host.apply(LEVEL);
        boolean clocks = level != null
                ? level.methods.stream().anyMatch(m -> m.name.equals("getOverworldClockTime") && m.desc.equals("()J"))
                : RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_CLOCK_HOST) >= 0;
        return clocks ? null : new SimpleRemapper(Map.of(LEVEL + ".getOverworldClockTime()J", "getDayTime"));
    }
}
