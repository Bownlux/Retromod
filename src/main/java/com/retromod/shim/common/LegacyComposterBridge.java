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

/** Rebuilds {@code ComposterBlock.COMPOSTABLES} inside each old mod, on hosts that moved compost chances into an item component. */
public final class LegacyComposterBridge {

    static final String COMPOSTER = "net/minecraft/world/level/block/ComposterBlock";
    static final String FIELD = "COMPOSTABLES";
    static final String MAP = "Lit/unimi/dsi/fastutil/objects/Object2FloatMap;";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/world/level/block/LegacyCompostables";
    /** The first host without the table; 26.3 made compostability the {@code minecraft:compostable} item component. */
    public static final String FIRST_HOST = "26.3";

    private LegacyComposterBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostLacksTable(host)) return;
        RebuiltApiClasses.registerTree(t, REBUILT);
        // A read of the old static field becomes a call that hands out this jar's copy of the rebuilt table.
        t.registerFieldRedirect(COMPOSTER, FIELD, MAP, REBUILT, "compostables", "()" + MAP);
    }

    /** Whether the host's composter has no chance table; offline or unreadable, a target from 26.3. */
    static boolean hostLacksTable(Function<String, ClassNode> host) {
        ClassNode composter = host.apply(COMPOSTER);
        if (composter == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) >= 0;
        }
        return composter.fields.stream().noneMatch(field -> field.name.equals(FIELD));
    }
}
