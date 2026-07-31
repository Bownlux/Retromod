/*
 * Retromod - compile-time stub of net.neoforged.neoforgespi.locating.IOrderedProvider.
 * Copyright (c) 2026 Bownlux. MIT License.
 *
 * This is not a copy of NeoForge's SPI. It exists only so that
 * com.retromod.locator.RetromodModLocator compiles without NeoForge on the
 * build classpath (Retromod ships as a single multi-loader artifact and uses
 * reflection for all other loader interaction - see RetromodNeoForge).
 *
 * AT RUNTIME under NeoForge, the loader's classloader provides the REAL
 * interface; this stub's .class is STRIPPED from the production jar by the
 * maven-jar-plugin <excludes> (same story as the @Mod stubs). Signatures here
 * are faithful to NeoForge loader 10.x/11.x (MC 1.21.0 → 26.2), verified by
 * javap against loader-11.0.13.jar.
 */
package net.neoforged.neoforgespi.locating;

public interface IOrderedProvider {
    int HIGHEST_SYSTEM_PRIORITY = 1000;
    int DEFAULT_PRIORITY = 0;
    int LOWEST_SYSTEM_PRIORITY = -1000;

    default int getPriority() {
        return DEFAULT_PRIORITY;
    }
}
