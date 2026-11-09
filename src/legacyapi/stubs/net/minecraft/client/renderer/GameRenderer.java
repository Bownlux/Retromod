// Compile-only stub of a 26.2 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.GameRenderState;

public class GameRenderer {
    private GameRenderer() {}

    public Camera mainCamera() { throw new UnsupportedOperationException(); }

    public GameRenderState gameRenderState() { throw new UnsupportedOperationException(); }
}
