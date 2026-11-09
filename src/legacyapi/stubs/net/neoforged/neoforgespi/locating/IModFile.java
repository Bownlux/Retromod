// Compile-only stub of a NeoForge 1.21.9 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforgespi.locating;

import net.neoforged.fml.jarcontents.JarContents;

import java.nio.file.Path;

public interface IModFile {
    JarContents getContents();
    Path getFilePath();
}
