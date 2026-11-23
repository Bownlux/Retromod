/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Builds a tree grower from the optional feature keys of 1.20.3 to 26.2, which 26.3 replaced with weighted lists. */
public final class LegacyTreeGrowerConstructorBridge {

    static final String GROWER = "net/minecraft/world/level/block/grower/TreeGrower";
    static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/world/level/block/grower/LegacyTreeGrowers";
    private static final String O = "Ljava/util/Optional;";
    static final String SHORT = "(Ljava/lang/String;" + O + O + O + ")V";
    static final String FULL = "(Ljava/lang/String;F" + O + O + O + O + O + O + ")V";
    /** The release that replaced the optional keys. */
    static final String VERSION = "26.3";

    private LegacyTreeGrowerConstructorBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostReplacedIt(host.apply(GROWER))) return;
        RebuiltApiClasses.registerTree(t, HELPER);
        for (String desc : new String[] {SHORT, FULL}) {
            t.registerConstructorRedirect(GROWER, desc, HELPER, "create", desc.replace(")V", ")L" + GROWER + ";"));
            t.limitRedirectToModsBefore(GROWER, "<init>", desc, VERSION);
        }
    }

    /** Whether the host's grower lacks the eight-argument constructor; offline or unreadable, a target from 26.3. */
    static boolean hostReplacedIt(ClassNode grower) {
        if (grower == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, VERSION) >= 0;
        return grower.methods.stream().noneMatch(m -> m.name.equals("<init>") && m.desc.equals(FULL));
    }
}
