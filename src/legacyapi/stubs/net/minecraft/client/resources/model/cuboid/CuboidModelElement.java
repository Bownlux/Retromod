// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model.cuboid;

import java.util.Map;
import net.minecraft.core.Direction;
import org.joml.Vector3fc;

// A record on the host. Its shade component changed in 26.3, so Retromod reads that one by reflection.
public final class CuboidModelElement {
    private CuboidModelElement() {}
    public Vector3fc from() { throw new UnsupportedOperationException(); }
    public Vector3fc to() { throw new UnsupportedOperationException(); }
    public Map<Direction, CuboidFace> faces() { throw new UnsupportedOperationException(); }
    public CuboidRotation rotation() { throw new UnsupportedOperationException(); }
    public int lightEmission() { throw new UnsupportedOperationException(); }
}
