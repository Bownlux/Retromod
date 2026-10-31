/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.neoforge.embedded.LegacyReloadRegistries;
import org.objectweb.asm.tree.ClassNode;

/** Keeps {@code AddServerReloadListenersEvent.getRegistryAccess()}, which NeoForge 26.3 removed, answering. */
public final class LegacyReloadRegistryBridge {

    static final String SUPPORT = "com/retromod/shim/neoforge/embedded/LegacyReloadRegistries";
    static final String EVENT = "net/neoforged/neoforge/event/AddServerReloadListenersEvent";
    static final String ACCESS = "Lnet/minecraft/core/RegistryAccess;";

    private LegacyReloadRegistryBridge() {}

    public static void register(RetromodTransformer t) {
        if (!hostLostRegistryAccess()) return;
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyReloadRegistries.class);
        t.registerMethodRedirect(EVENT, "getRegistryAccess", "()" + ACCESS,
                SUPPORT, "registryAccess", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
    }

    // Offline, where the host cannot be read, the version decides.
    static boolean hostLostRegistryAccess() {
        ClassNode event = ClassResourceInspector.read(EVENT);
        if (event == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        return event.methods.stream().noneMatch(m -> m.name.equals("getRegistryAccess"));
    }
}
