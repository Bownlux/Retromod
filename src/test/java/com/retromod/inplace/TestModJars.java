/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Small mod jars for in-place tests, shaped like the real metadata loaders read. */
final class TestModJars {

    private TestModJars() {}

    /** A Forge mod with only {@code META-INF/mods.toml}. */
    static Path forgeMod(Path file, String modId, String version, String minecraftRange)
            throws IOException {
        return jar(file, null, Map.of("META-INF/mods.toml",
                toml(modId, version, minecraftRange, "forge", "[40,)", "mandatory=true")));
    }

    /** A NeoForge mod with only {@code META-INF/neoforge.mods.toml}. */
    static Path neoForgeMod(Path file, String modId, String version, String minecraftRange)
            throws IOException {
        return jar(file, null, Map.of("META-INF/neoforge.mods.toml",
                toml(modId, version, minecraftRange, "neoforge", "[20.4,)",
                        "type=\"required\"")));
    }

    /** A Fabric mod, which Retromod leaves to Sinytra Connector on NeoForge. */
    static Path fabricMod(Path file, String modId, String minecraft) throws IOException {
        return jar(file, null, Map.of("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\""
                + modId + "\",\"version\":\"1.0.0\",\"depends\":{\"minecraft\":\"" + minecraft
                + "\"}}"));
    }

    /** A Forge mod carrying a manifest attribute, such as Retromod's output stamp. */
    static Path stampedForgeMod(Path file, String modId, String minecraftRange,
            String attribute, String value) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue(attribute, value);
        return jar(file, manifest, Map.of("META-INF/mods.toml",
                toml(modId, "1.0.0", minecraftRange, "forge", "[40,)", "mandatory=true")));
    }

    static String toml(String modId, String version, String minecraftRange, String loaderId,
            String loaderRange, String requirement) {
        return "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"" + modId + "\"\nversion=\"" + version + "\"\n"
                + "displayName=\"" + modId + "\"\n"
                + "[[dependencies." + modId + "]]\nmodId=\"" + loaderId + "\"\n" + requirement
                + "\nversionRange=\"" + loaderRange + "\"\n"
                + "[[dependencies." + modId + "]]\nmodId=\"minecraft\"\n" + requirement
                + "\nversionRange=\"" + minecraftRange + "\"\n";
    }

    static Path jar(Path file, Manifest manifest, Map<String, String> entries)
            throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Manifest actual = manifest;
        if (actual == null) {
            actual = new Manifest();
            actual.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        }
        try (OutputStream out = Files.newOutputStream(file);
             JarOutputStream jar = new JarOutputStream(out, actual)) {
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(entries).entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return file;
    }
}
