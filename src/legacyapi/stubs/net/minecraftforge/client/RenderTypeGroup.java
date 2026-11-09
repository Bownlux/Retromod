// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;

public final class RenderTypeGroup {
    public static final RenderTypeGroup EMPTY = null;
    public RenderTypeGroup(ChunkSectionLayer block, RenderType entity, RenderType entityFabulous) {}
    public RenderTypeGroup(ChunkSectionLayer block, RenderType entity) {}
    public ChunkSectionLayer block() { throw new UnsupportedOperationException(); }
    public RenderType entity() { throw new UnsupportedOperationException(); }
    public RenderType entityFabulous() { throw new UnsupportedOperationException(); }
}
