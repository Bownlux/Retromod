// Compile-only stub of a 26.2 host type for src/legacyapi; never packaged.
package com.mojang.blaze3d.systems;

import org.joml.Matrix4fStack;

public final class RenderSystem {
    private RenderSystem() {}

    public static Matrix4fStack getModelViewStack() { throw new UnsupportedOperationException(); }
    public static boolean isOnRenderThread() { throw new UnsupportedOperationException(); }
}
