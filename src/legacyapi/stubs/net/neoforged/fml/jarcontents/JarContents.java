// Compile-only stub of a NeoForge 1.21.9 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.fml.jarcontents;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.Collection;

public interface JarContents extends Closeable {
    Collection<Path> getContentRoots();
}
