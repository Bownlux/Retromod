// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.packs.repository;

import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;

// The resources supplier's own methods differ between 26.2 and 26.3, so only the type is named.
public class Pack {
    private Pack() {}
    public static Pack readMetaAndCreate(PackLocationInfo location, ResourcesSupplier resources, PackType type,
            PackSelectionConfig selection) { throw new UnsupportedOperationException(); }

    public interface ResourcesSupplier {}

    public enum Position {
        TOP, BOTTOM
    }
}
