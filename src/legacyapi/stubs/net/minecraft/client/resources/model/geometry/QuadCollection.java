// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model.geometry;

import java.util.List;
import net.minecraft.core.Direction;

public class QuadCollection {
    public static final QuadCollection EMPTY = null;
    public List<BakedQuad> getQuads(Direction side) { throw new UnsupportedOperationException(); }
    public List<BakedQuad> getAll() { throw new UnsupportedOperationException(); }
    public int materialFlags() { throw new UnsupportedOperationException(); }

    public static class Builder {
        public Builder() { throw new UnsupportedOperationException(); }
        public Builder addCulledFace(Direction side, BakedQuad quad) { throw new UnsupportedOperationException(); }
        public Builder addUnculledFace(BakedQuad quad) { throw new UnsupportedOperationException(); }
        public Builder addAll(QuadCollection quads) { throw new UnsupportedOperationException(); }
        public QuadCollection build() { throw new UnsupportedOperationException(); }
    }
}
