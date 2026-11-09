// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.packs.repository;

import java.util.function.Consumer;

public interface RepositorySource {
    void loadPacks(Consumer<Pack> sink);
}
