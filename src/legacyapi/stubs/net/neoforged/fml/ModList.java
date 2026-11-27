// Compile-only stub of a NeoForge 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.fml;

import java.util.List;
import java.util.Optional;

public class ModList {
    public static ModList get() { throw new UnsupportedOperationException(); }
    public List<ModContainer> getSortedMods() { throw new UnsupportedOperationException(); }
    // Also in NeoForge 1.21.1's loader.
    public Optional<? extends ModContainer> getModContainerById(String modId) { throw new UnsupportedOperationException(); }
}
