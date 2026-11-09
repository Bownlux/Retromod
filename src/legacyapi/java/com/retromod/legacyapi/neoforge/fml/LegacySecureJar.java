/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.fml;

import java.net.URI;
import java.nio.file.Path;

/** The part of SecureJarHandler's {@code SecureJar} that mods read from their own mod file. */
public interface LegacySecureJar {

    /** The mod's jar, or its folder in a development run. */
    Path getPrimaryPath();

    /** The root to resolve the mod's own files against. */
    Path getRootPath();

    /** A file in the mod, which may not exist. */
    Path getPath(String first, String... rest);

    ModuleDataProvider moduleDataProvider();

    /** The part of {@code SecureJar.ModuleDataProvider} mods read: where the jar is. */
    interface ModuleDataProvider {
        URI uri();
    }
}
