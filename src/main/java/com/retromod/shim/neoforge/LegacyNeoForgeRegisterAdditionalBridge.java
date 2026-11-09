/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.util.McReflect;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Points old RegisterAdditional listeners on NeoForge 1.21.11 and 26.x, which have only standalone models, at a class that exists. */
public final class LegacyNeoForgeRegisterAdditionalBridge {

    static final String NEO_OLD = "net/neoforged/neoforge/client/event/ModelEvent$RegisterAdditional";
    // Forge's name reaches NEO_OLD through the bulk event renames, and is redirected here too for the offline Forge chain.
    static final String FORGE_OLD = "net/minecraftforge/client/event/ModelEvent$RegisterAdditional";
    static final String STANDALONE = "net/neoforged/neoforge/client/event/ModelEvent$RegisterStandalone";
    public static final String REBUILT = RebuiltApiClasses.ROOT + "neoforge/client/event/LegacyRegisterAdditional";

    private LegacyNeoForgeRegisterAdditionalBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!McReflect.isNeoForge() || !hostDroppedRegisterAdditional(host)) return;
        RebuiltApiClasses.registerTree(t, REBUILT);
        // A subscriber class that names a missing event loses every listener it has, so the old name must resolve.
        t.registerClassRedirect(NEO_OLD, REBUILT);
        t.registerClassRedirect(FORGE_OLD, REBUILT);
    }

    /** Whether the host has standalone models and no RegisterAdditional; offline or on a server, the 1.21.11 or 26.1 layer stands for it. */
    static boolean hostDroppedRegisterAdditional(Function<String, ClassNode> host) {
        if (host.apply(NEO_OLD) != null) return false;
        ClassNode standalone = host.apply(STANDALONE);
        return standalone != null || host.apply("net/neoforged/neoforge/common/NeoForge") == null;
    }
}
