// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model.cuboid;

import net.minecraft.core.Direction;
import org.joml.Vector3fc;

// A record on the host.
public final class CuboidRotation {
    public CuboidRotation(Vector3fc origin, RotationValue value, boolean rescale) {}
    public Vector3fc origin() { throw new UnsupportedOperationException(); }
    public RotationValue value() { throw new UnsupportedOperationException(); }
    public boolean rescale() { throw new UnsupportedOperationException(); }

    public interface RotationValue {}

    // A record on the host.
    public static final class SingleAxisRotation implements RotationValue {
        public SingleAxisRotation(Direction.Axis axis, float angle) {}
        public Direction.Axis axis() { throw new UnsupportedOperationException(); }
        public float angle() { throw new UnsupportedOperationException(); }
    }

    // A record on the host.
    public static final class EulerXYZRotation implements RotationValue {
        private EulerXYZRotation() {}
        public float x() { throw new UnsupportedOperationException(); }
        public float y() { throw new UnsupportedOperationException(); }
        public float z() { throw new UnsupportedOperationException(); }
    }
}
