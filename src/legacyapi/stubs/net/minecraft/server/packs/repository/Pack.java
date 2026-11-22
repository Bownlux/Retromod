// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.packs.repository;

import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;

// The resources supplier's own methods differ between 26.2 and 26.3; only the 26.3 metadata read is named.
public class Pack {
    private Pack() {}
    public static Pack readMetaAndCreate(PackLocationInfo location, ResourcesSupplier resources, PackType type,
            PackSelectionConfig selection) { throw new UnsupportedOperationException(); }

    // 26.3 shape: a pack can open as several resource layers.
    public java.util.stream.Stream<net.minecraft.server.packs.PackResources> open() { throw new UnsupportedOperationException(); }

    public interface ResourcesSupplier {
        // 26.3 shape.
        default net.minecraft.server.packs.PackMetadataResources openMetadata(PackLocationInfo location) { throw new UnsupportedOperationException(); }
    }

    public enum Position {
        TOP, BOTTOM
    }
}
