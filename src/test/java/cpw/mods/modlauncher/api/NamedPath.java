/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package cpw.mods.modlauncher.api;

import java.nio.file.Path;

/**
 * Test stand-in with the public shape of modlauncher's record (checked with javap in
 * modlauncher 10.0.9 and 10.2.6), so the bootstrap's reflective hide can run in a unit test.
 */
public record NamedPath(String name, Path... paths) {}
