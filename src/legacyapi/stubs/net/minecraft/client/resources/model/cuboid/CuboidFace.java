// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model.cuboid;

import com.mojang.math.Quadrant;
import net.minecraft.core.Direction;

// A record on the host.
public final class CuboidFace {
    public CuboidFace(Direction cullForDirection, int tintIndex, String texture, UVs uvs, Quadrant rotation) {}
    public Direction cullForDirection() { throw new UnsupportedOperationException(); }
    public int tintIndex() { throw new UnsupportedOperationException(); }
    public String texture() { throw new UnsupportedOperationException(); }
    public UVs uvs() { throw new UnsupportedOperationException(); }
    public Quadrant rotation() { throw new UnsupportedOperationException(); }

    // A record on the host.
    public static final class UVs {
        public UVs(float minU, float minV, float maxU, float maxV) {}
        public float minU() { throw new UnsupportedOperationException(); }
        public float minV() { throw new UnsupportedOperationException(); }
        public float maxU() { throw new UnsupportedOperationException(); }
        public float maxV() { throw new UnsupportedOperationException(); }
        public float getVertexU(int vertex) { throw new UnsupportedOperationException(); }
        public float getVertexV(int vertex) { throw new UnsupportedOperationException(); }
    }
}
