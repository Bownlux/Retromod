/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Lets tests in other packages run the render stage pass against a stand-in host. */
public final class NeoForgeAbstractEventListenersAccess {

    private NeoForgeAbstractEventListenersAccess() {}

    public static byte[] applyRenderStages(byte[] classBytes, Function<String, ClassNode> host, String target) {
        return NeoForgeAbstractEventListeners.applyRenderStages(classBytes, host, target);
    }
}
