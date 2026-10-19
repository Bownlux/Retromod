/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import com.retromod.core.ForgeTomlChoice;
import com.retromod.util.ZipSecurity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one Forge-family mods toml declares: its mod ids, the first mod's version, and its
 * dependency rules.
 *
 * <p>In-place loading needs two answers before any mod code runs. Which mod ids a jar provides,
 * so an old copy never loads beside a current one, and whether the loader would load the old
 * jar untranslated at all. A jar the loader would refuse can be held back while Retromod
 * translates it; a jar it would load is left visible, because holding it back could break a
 * mod that depends on it. The rules here are written from the toml format and observed loader
 * messages, not from loader code.
 *
 * @param entry         the toml entry these facts come from
 * @param modLoader     the top-level language provider, such as {@code javafml}, or null
 * @param loaderVersion the top-level range that provider's version must meet, or null
 * @param modIds        every {@code [[mods]]} id, in file order
 * @param firstVersion  the first mod's version, with {@code ${file.jarVersion}} resolved from
 *                      the manifest, or null
 * @param dependencies  every {@code [[dependencies.*]]} block
 */
record ForgeModMetadata(String entry, String modLoader, String loaderVersion,
        List<String> modIds, String firstVersion, List<Dependency> dependencies) {

    static final String FORGE_TOML = ForgeTomlChoice.FORGE_TOML;
    static final String NEOFORGE_TOML = ForgeTomlChoice.NEOFORGE_TOML;

    private static final long MAX_TOML_BYTES = 1024L * 1024;
    private static final Pattern TABLE_SPLIT = Pattern.compile("(?m)^(?=[ \\t]*\\[)");
    private static final Pattern HEADER = Pattern.compile(
            "^[ \\t]*(\\[\\[?)[ \\t]*([^\\]]+?)[ \\t]*\\]\\]?");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "^[ \\t]*([A-Za-z0-9_.\"'-]+)[ \\t]*=[ \\t]*(.*)$");

    /** How strongly a dependency is declared. */
    enum Requirement { REQUIRED, OPTIONAL, INCOMPATIBLE, DISCOURAGED }

    /** One {@code [[dependencies.owner]]} block. */
    record Dependency(String modId, String versionRange, Requirement requirement) {}

    ForgeModMetadata {
        modIds = List.copyOf(modIds);
        dependencies = List.copyOf(dependencies);
    }

    /** The first mod id, which Forge and NeoForge use to tell duplicate files apart. */
    Optional<String> primaryModId() {
        return modIds.isEmpty() ? Optional.empty() : Optional.of(modIds.get(0));
    }

    /**
     * Reads the facts from {@code entryName} of an open jar. Empty when the jar has no such
     * entry. {@code manifest} resolves a {@code ${file.jarVersion}} version and may be null.
     */
    static Optional<ForgeModMetadata> read(JarFile jar, String entryName) throws IOException {
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) return Optional.empty();
        String text;
        try (InputStream input = jar.getInputStream(entry)) {
            text = new String(ZipSecurity.safeReadAllBytes(input, MAX_TOML_BYTES),
                    StandardCharsets.UTF_8);
        }
        return Optional.of(parse(entryName, text, jar.getManifest()));
    }

    /**
     * The facts from the toml that really describes the jar's code, which is the one
     * {@link ForgeTomlChoice} picks when both files exist.
     */
    static Optional<ForgeModMetadata> readEffective(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            String entry = ForgeTomlChoice.metadataEntry(jar);
            return entry == null ? Optional.empty() : read(jar, entry);
        }
    }

    /**
     * True only when the host loader would certainly read this toml and accept the jar as it
     * is. A "no" covers both a jar the loader skips (no toml it reads) and one it refuses at
     * discovery: a language provider version, Minecraft range, loader range, or library range
     * the host may not meet, or a required loader the host is not. These are the checks
     * {@code ForgeModTransformer} relaxes in a translated copy.
     *
     * <p>A wrong "yes" stops the loader before Retromod's entry point runs, so the jar is never
     * translated. A wrong "no" only keeps an old jar out of one launch that ends in a restart
     * anyway. So anything Retromod cannot check here reads as "no".
     *
     * @param hostToml        the toml the host loader reads: {@link #FORGE_TOML} on Forge and
     *                        NeoForge 1.20.1, {@link #NEOFORGE_TOML} on NeoForge 20.5 and newer
     * @param hostMinecraft   the host's Minecraft version
     * @param hostLoaderBuild the host's Forge or NeoForge version, or anything unreadable when
     *                        the loader did not report it
     */
    static boolean loaderWouldLoad(Optional<ForgeModMetadata> hostTomlFacts, String hostToml,
            String hostMinecraft, String hostLoaderBuild) {
        if (hostTomlFacts.isEmpty()) return false;
        ForgeModMetadata facts = hostTomlFacts.get();
        if (facts.modIds().isEmpty()) return false;
        boolean neoForgeHost = NEOFORGE_TOML.equals(hostToml);
        // A NeoForge 20.5+ host has no "forge" mod, and a Forge host has no "neoforge" mod.
        String hostLoader = neoForgeHost ? "neoforge" : "forge";
        String absentLoader = neoForgeHost ? "forge" : "neoforge";
        String build = MavenVersionRange.isVersion(hostLoaderBuild) ? hostLoaderBuild : null;
        if (!languageProviderAccepts(facts, neoForgeHost, build)) return false;
        for (Dependency dependency : facts.dependencies()) {
            if (dependency.requirement() == Requirement.INCOMPATIBLE
                    || dependency.requirement() == Requirement.DISCOURAGED) {
                continue;
            }
            String range = dependency.versionRange();
            if ("minecraft".equals(dependency.modId())) {
                if (range != null && !MavenVersionRange.certainlyContains(range, hostMinecraft)) {
                    return false;
                }
            } else if (absentLoader.equals(dependency.modId())) {
                if (dependency.requirement() == Requirement.REQUIRED) return false;
            } else if (hostLoader.equals(dependency.modId())) {
                if (range != null && !(build == null ? MavenVersionRange.openAbove(range)
                        : MavenVersionRange.certainlyContains(range, build))) {
                    return false;
                }
            } else if (range != null && !MavenVersionRange.openAbove(range)) {
                // The installed version is unknown here, and the loader checks an optional
                // dependency's range too when that mod is installed.
                return false;
            }
        }
        return true;
    }

    /**
     * Forge's {@code javafml} and {@code lowcodefml} report the Forge version, so its full
     * build and its major number must both fit, whichever of the two a host reports. NeoForge
     * 20.5 and newer report the FML version, which the bootstrap does not know, and any other
     * provider's version is unknown too. Those versions only grow, so a range an old mod met
     * on its own host still holds when it has no upper bound.
     */
    private static boolean languageProviderAccepts(ForgeModMetadata facts, boolean neoForgeHost,
            String build) {
        String range = facts.loaderVersion();
        if (range == null || facts.modLoader() == null) return false;
        boolean forgeProvider = "javafml".equals(facts.modLoader())
                || "lowcodefml".equals(facts.modLoader());
        if (forgeProvider && !neoForgeHost && build != null) {
            String major = build.split("[.\\-+]", 2)[0];
            return MavenVersionRange.certainlyContains(range, build)
                    && MavenVersionRange.certainlyContains(range, major);
        }
        return MavenVersionRange.openAbove(range);
    }

    static ForgeModMetadata parse(String entryName, String toml, Manifest manifest) {
        List<String> modIds = new ArrayList<>();
        String firstVersion = null;
        List<Dependency> dependencies = new ArrayList<>();
        Map<String, String> topLevel = Map.of();
        boolean first = true;
        for (String chunk : TABLE_SPLIT.split(toml)) {
            Matcher header = HEADER.matcher(chunk);
            boolean tableChunk = header.find();
            // Keys before the first table header belong to the file itself.
            if (first && !tableChunk) topLevel = keyValues(chunk);
            first = false;
            if (!tableChunk) continue;
            boolean arrayTable = header.group(1).equals("[[");
            String name = unquote(header.group(2).trim());
            Map<String, String> values = keyValues(chunk.substring(header.end()));
            if (arrayTable && name.equals("mods")) {
                String modId = values.get("modId");
                if (modId == null || modId.isBlank()) continue;
                modIds.add(modId.trim());
                if (firstVersion == null) {
                    firstVersion = resolveVersion(values.get("version"), manifest);
                }
            } else if (arrayTable && name.startsWith("dependencies.")) {
                String modId = values.get("modId");
                if (modId == null || modId.isBlank()) continue;
                dependencies.add(new Dependency(modId.trim(), values.get("versionRange"),
                        requirement(values)));
            }
        }
        return new ForgeModMetadata(entryName, blankToNull(topLevel.get("modLoader")),
                blankToNull(topLevel.get("loaderVersion")), modIds, firstVersion, dependencies);
    }

    /**
     * NeoForge's {@code type} wins over Forge's {@code mandatory}. A block with neither is
     * required, which is how both loaders read it.
     */
    private static Requirement requirement(Map<String, String> values) {
        String type = values.get("type");
        if (type != null) {
            return switch (type.trim().toLowerCase(Locale.ROOT)) {
                case "optional" -> Requirement.OPTIONAL;
                case "incompatible" -> Requirement.INCOMPATIBLE;
                case "discouraged" -> Requirement.DISCOURAGED;
                default -> Requirement.REQUIRED;
            };
        }
        String mandatory = values.get("mandatory");
        if (mandatory != null && mandatory.trim().equalsIgnoreCase("false")) {
            return Requirement.OPTIONAL;
        }
        return Requirement.REQUIRED;
    }

    private static String resolveVersion(String declared, Manifest manifest) {
        if (declared == null) return null;
        String version = declared.trim();
        if (version.contains("${file.jarVersion}")) {
            String fromManifest = manifest == null ? null
                    : manifest.getMainAttributes().getValue("Implementation-Version");
            if (fromManifest == null || fromManifest.isBlank()) return null;
            version = version.replace("${file.jarVersion}", fromManifest.trim());
        }
        return version.isBlank() ? null : version;
    }

    /** Single-line {@code key = value} pairs; multi-line strings are skipped. */
    private static Map<String, String> keyValues(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean inMultiLine = false;
        for (String line : body.split("\\R")) {
            if (inMultiLine) {
                if (line.contains("'''") || line.contains("\"\"\"")) inMultiLine = false;
                continue;
            }
            Matcher matcher = KEY_VALUE.matcher(line);
            if (!matcher.find()) continue;
            String raw = matcher.group(2).trim();
            if (raw.startsWith("'''") || raw.startsWith("\"\"\"")) {
                String rest = raw.substring(3);
                inMultiLine = !(rest.contains("'''") || rest.contains("\"\"\""));
                continue;
            }
            values.putIfAbsent(unquote(matcher.group(1)), value(raw));
        }
        return values;
    }

    /** A quoted string's contents, or a bare value up to a trailing comment. */
    private static String value(String raw) {
        if (raw.startsWith("\"") || raw.startsWith("'")) {
            char quote = raw.charAt(0);
            int end = raw.indexOf(quote, 1);
            return end < 0 ? raw.substring(1) : raw.substring(1, end);
        }
        int comment = raw.indexOf('#');
        return (comment < 0 ? raw : raw.substring(0, comment)).trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String unquote(String text) {
        String trimmed = text.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                        || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
