package net.neoforged.neoforgespi.locating;

import java.util.List;

/** Compile-only stub of NeoForge 20.2 to 20.4's IModLocator, which the loader supplies at runtime. */
public interface IModLocator extends IModProvider {
    // The list's element type is erased, so the bootstrap's override links against the real interface.
    List<?> scanMods();
}
