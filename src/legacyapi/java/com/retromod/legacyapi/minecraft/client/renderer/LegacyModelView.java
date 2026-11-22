/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

/** Minecraft 1.20.1's {@code RenderSystem.getModelViewStack()}, a PoseStack, which 1.20.5 turned into a Matrix4fStack. */
public final class LegacyModelView {

    // Old code pushed on one call and popped on a later one, so the render thread keeps one stack.
    private static final PoseStack RENDER_THREAD_STACK = new PoseStack();

    private LegacyModelView() {}

    /**
     * A PoseStack that starts from the game's model-view matrix. Changes to it stay in the stack: the host reads its
     * own matrix stack, and the old {@code applyModelViewMatrix} that copied the stack over is gone.
     */
    public static PoseStack stack() {
        // Another thread gets a stack of its own, as the old one belonged to the render thread.
        PoseStack stack = RenderSystem.isOnRenderThread() ? RENDER_THREAD_STACK : new PoseStack();
        // Between frames nothing is pushed, so the stack takes up the game's current matrix again.
        if (stack.isEmpty()) {
            stack.setIdentity();
            stack.mulPose(RenderSystem.getModelViewStack());
        }
        return stack;
    }
}
