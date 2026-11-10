// Compile-only stub of a 26.2 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.state.level;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;

public class CameraRenderState {
    public Frustum cullFrustum;
    public Matrix4f projectionMatrix;
    public Matrix4f viewRotationMatrix;

    public CameraRenderState() {}
}
