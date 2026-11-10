// Compile-only stub of a 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model.geometry;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import org.joml.Vector3fc;

public final class BakedQuad {
    public BakedQuad(Vector3fc position0, Vector3fc position1, Vector3fc position2, Vector3fc position3,
            long packedUV0, long packedUV1, long packedUV2, long packedUV3, Direction direction, MaterialInfo materialInfo) {}
    public Vector3fc position(int vertex) { throw new UnsupportedOperationException(); }
    public long packedUV(int vertex) { throw new UnsupportedOperationException(); }
    public Direction direction() { throw new UnsupportedOperationException(); }
    public MaterialInfo materialInfo() { throw new UnsupportedOperationException(); }

    // Only the members 26.2 and 26.3 share: the canonical constructor and the shade member differ between them.
    public static final class MaterialInfo {
        private MaterialInfo() {}
        public TextureAtlasSprite sprite() { throw new UnsupportedOperationException(); }
        public ChunkSectionLayer layer() { throw new UnsupportedOperationException(); }
        public RenderType itemRenderType() { throw new UnsupportedOperationException(); }
        public int tintIndex() { throw new UnsupportedOperationException(); }
        public int lightEmission() { throw new UnsupportedOperationException(); }
        public boolean isTinted() { throw new UnsupportedOperationException(); }
        public int flags() { throw new UnsupportedOperationException(); }
    }
}
