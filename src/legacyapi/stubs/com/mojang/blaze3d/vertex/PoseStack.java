// Compile-only stub of a 26.2 host type for src/legacyapi; never packaged.
package com.mojang.blaze3d.vertex;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;

public class PoseStack {
    public PoseStack() {}

    public void pushPose() { throw new UnsupportedOperationException(); }

    public void popPose() { throw new UnsupportedOperationException(); }

    public Pose last() { throw new UnsupportedOperationException(); }

    public boolean isEmpty() { throw new UnsupportedOperationException(); }

    public void setIdentity() { throw new UnsupportedOperationException(); }

    public void mulPose(Matrix4fc pose) { throw new UnsupportedOperationException(); }

    public void translate(float x, float y, float z) { throw new UnsupportedOperationException(); }

    public void scale(float x, float y, float z) { throw new UnsupportedOperationException(); }

    public void rotateAround(Quaternionfc rotation, float x, float y, float z) { throw new UnsupportedOperationException(); }

    public static final class Pose {
        private Pose() {}

        public Matrix4f pose() { throw new UnsupportedOperationException(); }

        public void set(Pose pose) { throw new UnsupportedOperationException(); }

        public void rotate(Quaternionfc rotation) { throw new UnsupportedOperationException(); }
    }
}
