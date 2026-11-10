// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.metadata.animation;

import java.util.List;
import java.util.Optional;
import net.minecraft.server.packs.metadata.MetadataSectionType;

// A record on the host; only its accessors matter to rebuilt code.
public final class AnimationMetadataSection {
    public static final MetadataSectionType<AnimationMetadataSection> TYPE = null;
    private AnimationMetadataSection() {}
    public FrameSize calculateFrameSize(int width, int height) { throw new UnsupportedOperationException(); }
    public Optional<List<AnimationFrame>> frames() { throw new UnsupportedOperationException(); }
    public int defaultFrameTime() { throw new UnsupportedOperationException(); }
    public boolean interpolatedFrames() { throw new UnsupportedOperationException(); }
}
