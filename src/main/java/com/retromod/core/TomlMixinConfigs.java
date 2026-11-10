/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.util.McReflect;
import com.retromod.util.RetromodLog;
import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Lists a jar's manifest {@code MixinConfigs} in its neoforge.mods.toml for NeoForge hosts that read only the toml. */
public final class TomlMixinConfigs {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    // Through 1.21.8 NeoForge ran on ModLauncher, whose Mixin plugin read the manifest attribute
    // beside FML's [[mixins]] list. 1.21.9 dropped ModLauncher, so only the toml is read from then on.
    static final String FIRST_TOML_ONLY_HOST = "1.21.9";
    static final String MANIFEST_ATTRIBUTE = "MixinConfigs";
    static final String NEOFORGE_TOML = "META-INF/neoforge.mods.toml";
    private static final String MANIFEST = "META-INF/MANIFEST.MF";

    // A config name is a resource path; anything else could break out of the quoted TOML string.
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_./-]+");
    private static final Pattern MIXINS_KEY = Pattern.compile("(?m)^\\s*mixins\\s*=");
    private static final Pattern CONFIG_VALUE = Pattern.compile("(?m)^\\s*config\\s*=\\s*[\"']([^\"']*)[\"']");

    /** The configs a jar's manifest names, and whether the jar is a game library rather than a mod. */
    public record ManifestConfigs(List<String> names, boolean gameLibrary) {
        public static final ManifestConfigs NONE = new ManifestConfigs(List.of(), false);
    }

    private TomlMixinConfigs() {}

    /** Whether a jar written for {@code targetMcVersion} goes to a NeoForge host that ignores the manifest attribute. */
    public static boolean appliesTo(String targetMcVersion) {
        return targetMcVersion != null && McReflect.isNeoForge()
                && !RetromodVersion.mcVersionExceeds(FIRST_TOML_ONLY_HOST, targetMcVersion);
    }

    /** Whether a mod's toml takes its manifest configs: a Forge toml just promoted, or a mod built before 1.21.9. */
    public static boolean listsFor(String targetMcVersion, boolean promotedForgeToml, String modMcVersion) {
        if (!appliesTo(targetMcVersion)) return false;
        if (promotedForgeToml) return true;
        return modMcVersion != null && RetromodVersion.mcVersionExceeds(FIRST_TOML_ONLY_HOST, modMcVersion);
    }

    /** The configs a manifest names, trimmed and in plain path form; empty without the attribute. */
    public static ManifestConfigs fromManifest(Manifest manifest) {
        if (manifest == null) return ManifestConfigs.NONE;
        // Forge loads a GAMELIBRARY jar, such as a bundled MixinExtras, outside the mod list, so it is left alone.
        boolean gameLibrary = "GAMELIBRARY".equals(manifest.getMainAttributes().getValue("FMLModType"));
        String value = manifest.getMainAttributes().getValue(MANIFEST_ATTRIBUTE);
        List<String> names = new ArrayList<>();
        if (value == null) return new ManifestConfigs(names, gameLibrary);
        for (String part : value.split(",")) {
            String name = plainPath(part);
            if (name.isEmpty() || names.contains(name)) continue;
            if (!SAFE_NAME.matcher(name).matches() || name.contains("..")) {
                LOGGER.debug(RetromodLog.DETAIL, "Left the mixin config name {} out of neoforge.mods.toml: "
                        + "it is not a plain resource path", name);
                continue;
            }
            names.add(name);
        }
        return new ManifestConfigs(names, gameLibrary);
    }

    /** The configs an extracted jar's manifest names. */
    public static ManifestConfigs fromDirectory(Path root) throws IOException {
        Path manifest = root.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest)) return ManifestConfigs.NONE;
        try (InputStream in = Files.newInputStream(manifest)) {
            return fromManifest(new Manifest(in));
        }
    }

    /** The configs the manifest of a jar held in memory names, such as a bundled jar's. */
    public static ManifestConfigs fromJarBytes(byte[] jarData) throws IOException {
        // Read by name: JarInputStream finds a manifest only among the first entries.
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jarData))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (!entry.isDirectory() && MANIFEST.equalsIgnoreCase(entry.getName())) {
                    byte[] bytes = ZipSecurity.safeReadAllBytes(in, 1024 * 1024);
                    return fromManifest(new Manifest(new ByteArrayInputStream(bytes)));
                }
            }
        }
        return ManifestConfigs.NONE;
    }

    /** The plain-path names of every file entry in a jar held in memory. */
    public static Set<String> entryNames(byte[] jarData) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jarData))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (!entry.isDirectory()) names.add(plainPath(entry.getName()));
            }
        }
        return names;
    }

    /**
     * {@code toml} with one {@code [[mixins]]} entry for each config the jar ships and the toml does not
     * name yet. FML refuses to start when a listed config is missing or another mod lists it too, so a
     * name whose file is in another jar is never listed.
     */
    public static String withConfigs(String toml, ManifestConfigs configs, Predicate<String> shipped) {
        // A static mixins = [...] array cannot take [[mixins]] tables, and its author already lists configs there.
        if (configs.gameLibrary() || configs.names().isEmpty() || MIXINS_KEY.matcher(toml).find()) return toml;
        Set<String> listed = listedConfigs(toml);
        StringBuilder added = new StringBuilder();
        for (String config : configs.names()) {
            if (listed.contains(config) || !shipped.test(config)) continue;
            added.append("\n[[mixins]]\nconfig = \"").append(config).append("\"\n");
        }
        if (added.length() == 0) return toml;
        String separator = toml.endsWith("\n") ? "" : "\n";
        return toml + separator + "\n# Listed from the manifest's MixinConfigs by Retromod: NeoForge 1.21.9 and newer"
                + " read mixin configs only here." + added;
    }

    /** The toml bytes with the manifest's shipped configs listed when this mod needs them, else the same array. */
    public static byte[] withConfigs(byte[] toml, ManifestConfigs configs, Predicate<String> shipped,
            String targetMcVersion, boolean promotedForgeToml, String modMcVersion) {
        if (!listsFor(targetMcVersion, promotedForgeToml, modMcVersion)) return toml;
        String text = new String(toml, StandardCharsets.UTF_8);
        String listed = withConfigs(text, configs, shipped);
        return listed.equals(text) ? toml : listed.getBytes(StandardCharsets.UTF_8);
    }

    /** Lists an extracted jar's shipped manifest configs in its neoforge.mods.toml when this mod needs them. */
    public static boolean listInDirectory(Path root, String targetMcVersion, boolean promotedForgeToml,
            String modMcVersion) throws IOException {
        Path toml = root.resolve(NEOFORGE_TOML);
        if (!listsFor(targetMcVersion, promotedForgeToml, modMcVersion) || !Files.isRegularFile(toml)) return false;
        ManifestConfigs configs = fromDirectory(root);
        if (configs.names().isEmpty()) return false;
        String text = Files.readString(toml, StandardCharsets.UTF_8);
        String listed = withConfigs(text, configs, name -> Files.isRegularFile(root.resolve(name)));
        if (listed.equals(text)) return false;
        Files.writeString(toml, listed, StandardCharsets.UTF_8);
        LOGGER.debug(RetromodLog.DETAIL, "Listed the manifest's mixin configs {} in neoforge.mods.toml", configs.names());
        return true;
    }

    /** The configs a toml already names, in plain path form. */
    private static Set<String> listedConfigs(String toml) {
        Set<String> listed = new HashSet<>();
        Matcher matcher = CONFIG_VALUE.matcher(toml);
        while (matcher.find()) listed.add(plainPath(matcher.group(1)));
        return listed;
    }

    /** A resource name without surrounding space or a leading {@code /} or {@code ./}, as FML resolves it. */
    static String plainPath(String name) {
        String path = name.trim();
        while (path.startsWith("/") || path.startsWith("./")) {
            path = path.startsWith("/") ? path.substring(1) : path.substring(2);
        }
        return path;
    }
}
