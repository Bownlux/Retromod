/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Lets tests in another package register the bridge against a stand-in host. */
public final class LegacyDefaultComponentsBridgeAccess {

    private LegacyDefaultComponentsBridgeAccess() {}

    public static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        LegacyDefaultComponentsBridge.register(t, host);
    }
}
