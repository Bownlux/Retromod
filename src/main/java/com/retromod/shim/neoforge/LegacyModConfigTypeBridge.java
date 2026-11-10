/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.tree.ClassNode;

/** Keeps a mod's COMMON and SERVER configs working where FancyModLoader renamed the config types. */
public final class LegacyModConfigTypeBridge {

    static final String TYPE = "net/neoforged/fml/config/ModConfig$Type";
    static final String CONTAINER = "net/neoforged/fml/ModContainer";
    static final String TRACKER = "net/neoforged/fml/config/ConfigTracker";
    public static final String HELPER = RebuiltApiClasses.ROOT + "neoforge/fml/LegacyModConfigs";
    /** NeoForge 26.3 beta 43 (FancyModLoader 12.0.8) is the first with the new names. */
    static final String FIRST_RENAMED_VERSION = "26.3";

    private static final String L_TYPE = "L" + TYPE + ";";
    private static final String L_SPEC = "Lnet/neoforged/fml/config/IConfigSpec;";
    private static final String L_CONTAINER = "L" + CONTAINER + ";";
    private static final String L_CONFIG = "Lnet/neoforged/fml/config/ModConfig;";
    static final String CONTAINER_REGISTER = "(" + L_TYPE + L_SPEC + ")V";
    static final String TRACKER_REGISTER = "(" + L_TYPE + L_SPEC + L_CONTAINER + ")" + L_CONFIG;

    private LegacyModConfigTypeBridge() {}

    /** Registers the bridge when the host has the renamed types. */
    public static void register(RetromodTransformer t) {
        if (hostRenamedTypes()) registerRedirects(t);
    }

    /** Unconditional registration, for the Forge config bridge and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer t) {
        // COMMON, loaded on each side from config/ and never synced, is now LOCAL. SERVER, loaded
        // per world with a world override and synced to clients, is now SYNCED. Same ordinals.
        t.registerFieldRedirect(TYPE, "COMMON", TYPE, "LOCAL");
        t.registerFieldRedirect(TYPE, "SERVER", TYPE, "SYNCED");

        // The default file name follows the type, so the same configs would move to modid-local.toml
        // and modid-synced.toml, and settings a player already made would be left behind.
        RebuiltApiClasses.registerTree(t, HELPER);
        oldModCall(t, CONTAINER, "registerConfig", CONTAINER_REGISTER, "(" + L_CONTAINER + L_TYPE + L_SPEC + ")V");
        oldModCall(t, TRACKER, "registerConfig", TRACKER_REGISTER,
                "(L" + TRACKER + ";" + L_TYPE + L_SPEC + L_CONTAINER + ")" + L_CONFIG);

        // Config libraries such as Balm and PuzzlesLib build file names and config ids from the type's
        // name, and parse names back into types.
        oldModCall(t, TYPE, "extension", "()Ljava/lang/String;", "(" + L_TYPE + ")Ljava/lang/String;");
        oldModCall(t, TYPE, "name", "()Ljava/lang/String;", "(" + L_TYPE + ")Ljava/lang/String;");
        oldModCall(t, TYPE, "valueOf", "(Ljava/lang/String;)" + L_TYPE, "(Ljava/lang/String;)" + L_TYPE);
    }

    /** Read from the host's own config type. Offline, a NeoForge 26.3 target has the new names. */
    public static boolean hostRenamedTypes() {
        ClassNode type = ClassResourceInspector.read(TYPE);
        if (type == null) {
            String host = RetromodVersion.TARGET_MC_VERSION;
            return host != null && RetromodVersion.compareMcVersions(host, FIRST_RENAMED_VERSION) >= 0;
        }
        return type.fields.stream().noneMatch(field -> field.name.equals("COMMON"));
    }

    // The host still has each member, so a mod built for the new names keeps the host's own behavior.
    private static void oldModCall(RetromodTransformer t, String owner, String name, String desc, String helperDesc) {
        boolean instance = !name.equals("valueOf");
        t.registerMethodRedirect(owner, name, desc, HELPER, name, helperDesc, instance);
        t.limitRedirectToModsBefore(owner, name, desc, FIRST_RENAMED_VERSION);
    }
}
