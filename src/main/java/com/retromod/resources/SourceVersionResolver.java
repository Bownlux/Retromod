/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.core.ModVersionDetector;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.util.ZipSecurity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** Works out the Minecraft version a mod jar was built for, the same way for every transform path. */
public final class SourceVersionResolver {

    /** The version every pre-Flattening Forge mod is treated as, matching the 1.12 class bridge. */
    static final String PRE_FLATTENING_FORGE = "1.12.2";

    private static final Pattern NUMERIC_VERSION = Pattern.compile("\\d+\\.\\d+[0-9A-Za-z.+-]*");
    private static final int MAX_VERSION_LENGTH = 64;
    /** Class names only a pre-Flattening Forge mod links against. */
    private static final List<String> LEGACY_FORGE_CLASS_MARKERS = List.of(
            // The 1.12 lifecycle events, which Forge 1.13 moved to fml/event/lifecycle.
            "net/minecraftforge/fml/common/event/FML",
            // The 1.12 GameRegistry, which 1.13 deleted.
            "net/minecraftforge/fml/common/registry/GameRegistry",
            // MCP's net/minecraft/init/ holders, which 1.14 moved.
            "net/minecraft/init/Blocks",
            "net/minecraft/init/Items",
            // The 1.7.10 FML package.
            "cpw/mods/fml/");
    private static final long MAX_CLASS_SCAN_BYTES = ZipSecurity.DEFAULT_MAX_TOTAL_SIZE;

    private SourceVersionResolver() {
    }

    /** What a jar says about itself: its version or null, its metadata's loader, and its mod ids. */
    public record Resolution(String minecraftVersion, MigrationContext.Loader loader,
                             Set<String> modIds) {
        public Resolution {
            modIds = Set.copyOf(modIds);
        }

        static Resolution unknown() {
            return new Resolution(null, MigrationContext.Loader.UNKNOWN, Set.of());
        }
    }

    /** Reads a mod jar on disk. An unreadable jar resolves to unknown rather than failing. */
    public static Resolution inspect(Path jar) {
        // Read the declared version the same way ModVersionDetector does for every loader. This
        // needs the source jar, not an extracted tree: the runtime Forge path writes a mods.toml
        // into the tree for a 1.12 mod, and that file would make the tree look modern.
        ModVersionInfo info = null;
        try {
            info = ModVersionDetector.metadataOnly().detectVersion(jar);
        } catch (IOException | RuntimeException unreadable) {
            // Malformed metadata still leaves the jar's contents as evidence.
        }
        // Keep the declared version only when it is a real version.
        String declared = info == null ? null : numericVersion(info.targetMcVersion());
        MigrationContext.Loader loader = info == null
                ? MigrationContext.Loader.UNKNOWN : MigrationContext.Loader.of(info.modLoaderType());
        Set<String> modIds = new LinkedHashSet<>();
        if (info != null && info.modId() != null && !"unknown".equals(info.modId())) {
            modIds.add(info.modId());
        }
        if (declared != null) {
            return new Resolution(declared, loader, modIds);
        }
        // Otherwise fall back to evidence in the jar itself, and with none the version stays null.
        // It never guesses, and never reads a pack format, which many 1.12 mods set wrong. Without
        // a toml, the detector reads Forge metadata only from mcmod.info or a 1.12-shaped @Mod
        // annotation, so Forge metadata there is itself evidence of a 1.12 mod.
        boolean legacyForgeMetadata = loader == MigrationContext.Loader.FORGE;
        if (isPreFlatteningForgeJar(jar, legacyForgeMetadata)) {
            return new Resolution(PRE_FLATTENING_FORGE, MigrationContext.Loader.FORGE, modIds);
        }
        return new Resolution(null, loader, modIds);
    }

    /** Reads a nested jar held in memory, such as a jar-in-jar entry before it is rewritten. */
    public static Resolution inspect(byte[] jar) {
        // The checks read a jar file on disk, so the bytes go to a temporary file.
        Path file = null;
        try {
            file = Files.createTempFile("retromod-source-", ".jar");
            Files.write(file, jar);
            return inspect(file);
        } catch (IOException unwritable) {
            return Resolution.unknown();
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // A leftover temporary file does not change the result.
                }
            }
        }
    }

    /** The version a jar on disk was built for, or null when unknown. */
    public static String resolve(Path jar) {
        return inspect(jar).minecraftVersion();
    }

    /** The declared version when it is a real version, or null. */
    public static String numericVersion(String declared) {
        if (declared == null) return null;
        // Placeholders such as ${mcversion}, wildcards, and anything that does not start with
        // major.minor are unknown, never old.
        String trimmed = declared.trim();
        if (trimmed.length() > MAX_VERSION_LENGTH || !NUMERIC_VERSION.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }

    /** Whether a jar is a Forge mod from before the 1.13 Flattening. */
    static boolean isPreFlatteningForgeJar(Path jar, boolean legacyForgeMetadata) {
        try (JarFile file = new JarFile(jar.toFile())) {
            // Every 1.13 and newer Forge or NeoForge mod ships a toml, and a Fabric or Quilt mod its
            // own metadata, so a jar with any of them is never old.
            if (file.getEntry("META-INF/mods.toml") != null
                    || file.getEntry("META-INF/neoforge.mods.toml") != null
                    || file.getEntry("fabric.mod.json") != null
                    || file.getEntry("quilt.mod.json") != null) {
                return false;
            }
            // Without one, a 1.12 mod ships mcmod.info or, failing that, links against a class only
            // the old Forge and MCP names had. The class scan stops at a total size limit.
            if (legacyForgeMetadata || file.getEntry("mcmod.info") != null) return true;
            long remaining = MAX_CLASS_SCAN_BYTES;
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements() && remaining > 0) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                byte[] bytes;
                try (InputStream input = file.getInputStream(entry)) {
                    bytes = ZipSecurity.safeReadAllBytes(input,
                            Math.min(ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, remaining));
                }
                remaining -= bytes.length;
                // Class names sit in the constant pool as plain text, so a byte search finds them.
                String constants = new String(bytes, StandardCharsets.ISO_8859_1);
                for (String marker : LEGACY_FORGE_CLASS_MARKERS) {
                    if (constants.contains(marker)) return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }
}
