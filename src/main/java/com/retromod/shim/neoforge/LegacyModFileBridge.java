/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;

/** Keeps a mod's lookups of its own files working after NeoForge 1.21.9 dropped SecureJarHandler. */
public final class LegacyModFileBridge {

    static final String MOD_FILE = "net/neoforged/neoforgespi/locating/IModFile";
    static final String LOADER_MOD_FILE = "net/neoforged/fml/loading/moddiscovery/ModFile";
    static final String SECURE_JAR = "cpw/mods/jarhandling/SecureJar";
    static final String HELPER = RebuiltApiClasses.ROOT + "neoforge/fml/LegacyModFiles";
    static final String LEGACY_JAR = RebuiltApiClasses.ROOT + "neoforge/fml/LegacySecureJar";

    static final String FIND_RESOURCE = "([Ljava/lang/String;)Ljava/nio/file/Path;";
    static final String GET_SECURE_JAR = "()L" + SECURE_JAR + ";";

    private LegacyModFileBridge() {}

    /** Registers on hosts from NeoForge 1.21.9 on, which have neither member nor SecureJarHandler. */
    public static void register(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, HELPER);
        // Mods read getRootPath and moduleDataProvider().uri() from the jar; the class goes before
        // the calls so each call is also matched in its rewritten form.
        t.registerClassRedirect(SECURE_JAR, LEGACY_JAR);
        t.registerClassRedirect(SECURE_JAR + "$ModuleDataProvider", LEGACY_JAR + "$ModuleDataProvider");

        // Libraries such as Architectury, Balm, JEI, Patchouli and PuzzlesLib read a mod's bundled
        // files through findResource, which FancyModLoader 10 removed with no replacement of that shape.
        for (String owner : new String[]{MOD_FILE, LOADER_MOD_FILE}) {
            t.registerMethodRedirect(owner, "findResource", FIND_RESOURCE, HELPER, "findResource",
                    "(L" + MOD_FILE + ";[Ljava/lang/String;)Ljava/nio/file/Path;", true);
            t.registerMethodRedirect(owner, "getSecureJar", GET_SECURE_JAR, HELPER, "getSecureJar",
                    "(L" + MOD_FILE + ";)L" + LEGACY_JAR + ";", true);
        }
    }
}
