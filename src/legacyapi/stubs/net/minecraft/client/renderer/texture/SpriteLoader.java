// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.texture;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.ResourceManager;

public class SpriteLoader {
    private SpriteLoader() {}
    public static SpriteLoader create(TextureAtlas atlas) { throw new UnsupportedOperationException(); }
    public CompletableFuture<Preparations> loadAndStitch(ResourceManager manager, Identifier atlasInfoLocation, int mipLevel,
            Executor executor, Set<MetadataSectionType<?>> metadataSections) { throw new UnsupportedOperationException(); }

    // A record on the host.
    public static final class Preparations {
        private Preparations() {}
        public CompletableFuture<Void> readyForUpload() { throw new UnsupportedOperationException(); }
    }
}
