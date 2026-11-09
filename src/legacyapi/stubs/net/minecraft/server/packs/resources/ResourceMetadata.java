// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.packs.resources;

import java.util.Optional;
import net.minecraft.server.packs.metadata.MetadataSectionType;

public interface ResourceMetadata {
    <T> Optional<T> getSection(MetadataSectionType<T> type);
}
