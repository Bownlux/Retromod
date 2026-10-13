/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Which of a jar's two loader metadata files describes its code.
 *
 * <p>MCreator has left a {@code META-INF/neoforge.mods.toml} from an earlier NeoForge export in
 * Forge 1.20.1 jars, beside the real {@code META-INF/mods.toml}. Read first, the stale file said
 * the mod was already built for the host, so a Forge mod was loaded unconverted and registered
 * nothing. When both files exist and the classes carry Forge's {@code @Mod} rather than
 * NeoForge's, {@code mods.toml} is the real one.
 */
public final class ForgeTomlChoice {

    public static final String NEOFORGE_TOML = "META-INF/neoforge.mods.toml";
    public static final String FORGE_TOML = "META-INF/mods.toml";
    private static final String FORGE_MOD = "Lnet/minecraftforge/fml/common/Mod;";
    private static final String NEOFORGE_MOD = "Lnet/neoforged/fml/common/Mod;";
    private static final long MAX_CLASS_BYTES = 4L << 20;

    private ForgeTomlChoice() {}

    /** The metadata entry to read, or null when the jar has neither file. */
    public static String metadataEntry(JarFile jar) throws IOException {
        boolean neo = jar.getEntry(NEOFORGE_TOML) != null;
        boolean forge = jar.getEntry(FORGE_TOML) != null;
        if (neo && forge) return codeIsForge(jar) ? FORGE_TOML : NEOFORGE_TOML;
        return neo ? NEOFORGE_TOML : forge ? FORGE_TOML : null;
    }

    /** Whether an extracted jar's {@code neoforge.mods.toml} is a leftover beside Forge metadata. */
    public static boolean hasStaleNeoForgeToml(Path root) throws IOException {
        if (!Files.exists(root.resolve(NEOFORGE_TOML)) || !Files.exists(root.resolve(FORGE_TOML))) return false;
        try (Stream<Path> files = Files.walk(root)) {
            Iterable<Path> classes = files.filter(p -> p.toString().endsWith(".class"))::iterator;
            for (Path file : classes) {
                if (Files.size(file) > MAX_CLASS_BYTES) continue;
                Boolean forge = forgeMod(Files.readAllBytes(file));
                if (forge != null) return forge;
            }
        }
        return false;
    }

    private static boolean codeIsForge(JarFile jar) throws IOException {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (!entry.getName().endsWith(".class") || entry.getSize() > MAX_CLASS_BYTES) continue;
            try (InputStream in = jar.getInputStream(entry)) {
                Boolean forge = forgeMod(in.readNBytes((int) MAX_CLASS_BYTES));
                if (forge != null) return forge;
            }
        }
        return false;
    }

    /** True for Forge's {@code @Mod}, false for NeoForge's, null when the class has neither. */
    private static Boolean forgeMod(byte[] classBytes) {
        String constants = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (constants.contains(NEOFORGE_MOD)) return false;
        if (constants.contains(FORGE_MOD)) return true;
        return null;
    }
}
