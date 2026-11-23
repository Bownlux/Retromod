/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.LegacyItemRendererBridge;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Rebuilds Fabric API's BuiltinItemRendererRegistry inside each old Fabric mod on hosts whose Fabric API removed it. */
public final class LegacyBuiltinItemRendererBridge {

    static final String OLD_API = "net/fabricmc/fabric/api/client/rendering/v1/";
    public static final String OLD_REGISTRY = OLD_API + "BuiltinItemRendererRegistry";
    static final String ROOT = RebuiltApiClasses.ROOT + "fabric/client/rendering/";
    static final String REGISTRY = ROOT + "LegacyBuiltinItemRendererRegistry";

    /** Fabric API removed the registry with Minecraft's builtin item models, so mods built for 1.21.4 and newer never name it. */
    public static final String FIRST_VERSION_WITHOUT = "1.21.4";

    /** The first host the bridge registers on, from the Fabric 1.21.11 to 26.1 shim; the item definitions follow it. */
    public static final String FIRST_HOST = "26.1";

    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    private LegacyBuiltinItemRendererBridge() {}

    /** Registers the rebuilt registry where the host's Fabric API lacks the old one; offline, from a 1.21.4 target. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!registryRemoved(host.apply(OLD_REGISTRY))) return;
        RebuiltApiClasses.registerTree(t, REGISTRY);
        // The registry draws through the rebuilt item renderer and its item model, which Forge's and NeoForge's bridges
        // also use; an old BlockEntityWithoutLevelRenderer subclass extends that rebuilt base.
        LegacyItemRendererBridge.register(t, List.of(), FIRST_VERSION_WITHOUT, null);
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
        for (String[] move : List.of(new String[]{OLD_REGISTRY, REGISTRY},
                new String[]{OLD_REGISTRY + "$DynamicItemRenderer", REGISTRY + "$DynamicItemRenderer"},
                new String[]{OLD_API + "BuiltinItemRenderer", ROOT + "LegacyBuiltinItemRenderer"})) {
            t.registerClassRedirect(move[0], move[1]);
            t.limitClassRedirectToModsBefore(move[0], FIRST_VERSION_WITHOUT);
        }
    }

    /** Whether the host's Fabric API lacks the old registry; unreadable, a host from 1.21.4 lacks it. */
    static boolean registryRemoved(ClassNode registry) {
        if (registry != null) return false;
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_VERSION_WITHOUT) >= 0;
    }
}
