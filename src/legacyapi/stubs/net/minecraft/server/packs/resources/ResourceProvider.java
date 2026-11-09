// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.packs.resources;

import java.util.Optional;
import net.minecraft.resources.Identifier;

public interface ResourceProvider {
    Optional<Resource> getResource(Identifier id);
}
