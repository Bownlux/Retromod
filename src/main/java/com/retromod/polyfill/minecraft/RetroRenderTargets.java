/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

/** Keeps old binds of the main render target working after 1.21.5 removed RenderTarget.bindWrite. */
public final class RetroRenderTargets {

    // Only Mojang-named hosts call this helper: NeoForge, Forge, and Fabric from 26.1.
    private static final String MAIN_TARGET = "com.mojang.blaze3d.pipeline.MainTarget";

    private RetroRenderTargets() {}

    /** Old {@code RenderTarget.bindWrite(boolean)}, with the target as argument 0. */
    public static void bindWrite(Object target, boolean setViewport) {
        java.util.Objects.requireNonNull(target, "target");
        // Since 1.21.5 each render pass names its own target and draws default to the main one,
        // so rebinding the main target after off-screen work has nothing left to do.
        if (isMainTarget(target)) return;
        // An off-screen target can no longer be bound. Fail the way the removed method did.
        throw new NoSuchMethodError("RenderTarget.bindWrite(boolean) on " + target.getClass().getName()
                + ": since Minecraft 1.21.5 a draw reaches an off-screen render target only through"
                + " a render pass. Retromod has no replacement for this call yet. Report it with the"
                + " mod and its version.");
    }

    static boolean isMainTarget(Object target) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            if (MAIN_TARGET.equals(type.getName())) return true;
        }
        return false;
    }
}
