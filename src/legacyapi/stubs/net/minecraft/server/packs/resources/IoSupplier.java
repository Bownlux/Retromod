// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.packs.resources;

import java.io.InputStream;
import java.nio.file.Path;

public interface IoSupplier<T> {
    static IoSupplier<InputStream> create(Path path) { throw new UnsupportedOperationException(); }
}
