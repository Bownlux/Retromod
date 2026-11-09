/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;

import java.util.List;

/** Moves Forge's removed folder pack onto the vanilla one, with a rebuilt base for old subclasses. */
public final class LegacyPathPackResourcesBridge {

    static final String FORGE_PACK = "net/minecraftforge/resource/PathPackResources";
    static final String VANILLA_PACK = "net/minecraft/server/packs/PathPackResources";
    static final String PACK_LOCATION = "net/minecraft/server/packs/PackLocationInfo";
    static final String REBUILT = RebuiltApiClasses.ROOT + "forge/resource/LegacyPathPackResources";
    static final String OLD_CTOR = "(Ljava/lang/String;ZLjava/nio/file/Path;)V";
    static final String GET_SOURCE = "()Ljava/nio/file/Path;";
    static final String RESOLVE = "([Ljava/lang/String;)Ljava/nio/file/Path;";
    private static final String SOURCE_OF = "(L" + VANILLA_PACK + ";)Ljava/nio/file/Path;";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    private LegacyPathPackResourcesBridge() {}

    /** Registers where the Forge host lacks its own PathPackResources; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        // Forge 1.20.1 ships the class and Forge 26.2 and 26.3 do not. Hosts between them were not checked.
        if (!ForgeHostShape.matches(List.of(VANILLA_PACK, PACK_LOCATION), List.of(FORGE_PACK))) return;
        RebuiltApiClasses.registerTree(t, REBUILT);
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);

        // Forge 26.2 builds each mod's pack as a vanilla PathPackResources, so instanceof and casts must name it.
        t.registerClassRedirect(FORGE_PACK, VANILLA_PACK);
        t.registerConstructorRedirect(FORGE_PACK, OLD_CTOR, REBUILT, "create",
                "(Ljava/lang/String;ZLjava/nio/file/Path;)L" + VANILLA_PACK + ";");

        // The redirect writes the vanilla name into a subclass's extends slot first, so the rebase is keyed on it.
        // A subclass written against vanilla keeps vanilla's behavior there, and its 1.20.1 constructor links again.
        t.registerSuperclassRebase(VANILLA_PACK, REBUILT);

        // getSource() reads the folder of any pack. A super.getSource() in an override goes to the
        // base's own answer, so it never runs that override again.
        for (String owner : List.of(FORGE_PACK, VANILLA_PACK)) {
            t.registerMethodRedirect(owner, "getSource", GET_SOURCE, REBUILT, "sourceOf", SOURCE_OF, true);
            // Only an old subclass's super.resolve(...) names the base; it moves to the rebuilt one.
            t.registerMethodRedirect(owner, "resolve", RESOLVE, REBUILT, "resolve", RESOLVE);
        }
        t.registerSuperCallHelper(REBUILT, "sourceOf", SOURCE_OF, "superSourceOf");
    }
}
