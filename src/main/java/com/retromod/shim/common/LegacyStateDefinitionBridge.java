/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;

/** Lets a block built before 1.21.2 add a state property its vanilla base class now adds itself. */
public final class LegacyStateDefinitionBridge {

    static final String BUILDER = "net/minecraft/world/level/block/state/StateDefinition$Builder";
    static final String ADD = "([Lnet/minecraft/world/level/block/state/properties/Property;)L" + BUILDER + ";";
    static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/world/level/block/state/LegacyStateDefinitions";
    static final String MULTIFACE = "net/minecraft/world/level/block/MultifaceBlock";
    /** The release that gave MultifaceBlock its own WATERLOGGED, which mods' subclasses had added themselves. */
    static final String VERSION = "1.21.2";

    private LegacyStateDefinitionBridge() {}

    public static void register(RetromodTransformer t) {
        if (!hostAddsIt(ClassResourceInspector.read(MULTIFACE))) return;
        RebuiltApiClasses.registerTree(t, HELPER);
        t.registerMethodRedirect(BUILDER, "add", ADD, HELPER, "add", "(L" + BUILDER + ";" + ADD.substring(1), true);
        t.limitRedirectToModsBefore(BUILDER, "add", ADD, VERSION);
    }

    /** Whether the host's MultifaceBlock declares WATERLOGGED; offline, whether the target is at least 1.21.2. */
    static boolean hostAddsIt(ClassNode multiface) {
        if (multiface == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, VERSION) >= 0;
        return multiface.fields.stream().anyMatch(field -> field.name.equals("WATERLOGGED"));
    }
}
