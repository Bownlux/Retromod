/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import com.retromod.embedder.ModVersionInfo;
import com.google.gson.*;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * Detects which Minecraft version a mod was built for, from its metadata.
 */
public class ModVersionDetector {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Retromod-Detector");
    private static final Gson GSON = new GsonBuilder().create();

    public ModVersionInfo detectVersion(Path modJarPath) throws IOException {
        try (JarFile jar = new JarFile(modJarPath.toFile())) {
            JarEntry fabricEntry = jar.getJarEntry("fabric.mod.json");
            if (fabricEntry != null) {
                return parseFabricMod(jar, fabricEntry);
            }

            JarEntry forgeEntry = jar.getJarEntry("META-INF/mods.toml");
            if (forgeEntry != null) {
                return parseForgeMod(jar, forgeEntry);
            }

            JarEntry legacyForgeEntry = jar.getJarEntry("mcmod.info");
            if (legacyForgeEntry != null) {
                return parseLegacyForgeMod(jar, legacyForgeEntry);
            }

            JarEntry neoEntry = jar.getJarEntry("META-INF/neoforge.mods.toml");
            if (neoEntry != null) {
                return parseNeoForgeMod(jar, neoEntry);
            }

            return null;
        }
    }

    private ModVersionInfo parseFabricMod(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream is = jar.getInputStream(entry);
             Reader reader = new InputStreamReader(is)) {

            JsonObject json = GSON.fromJson(reader, JsonObject.class);

            String modId = json.has("id") ? json.get("id").getAsString() : "unknown";
            String modVersion = json.has("version") ? json.get("version").getAsString() : "unknown";
            String targetMcVersion = extractFabricMcVersion(json);
            String fabricApiVersion = extractFabricApiVersion(json);

            Set<String> packages = scanModPackages(jar, modId);
            Set<String> apiDeps = scanApiDependencies(jar);
            boolean usesRemoved = checkForRemovedApis(apiDeps);
            
            return new ModVersionInfo(
                modId,
                modVersion,
                targetMcVersion,
                "fabric",
                fabricApiVersion,
                packages,
                apiDeps,
                usesRemoved
            );
        }
    }
    
    private String extractFabricMcVersion(JsonObject json) {
        if (!json.has("depends")) return null;

        JsonObject depends = json.getAsJsonObject("depends");

        if (depends.has("minecraft")) {
            JsonElement mc = depends.get("minecraft");
            if (mc.isJsonPrimitive()) {
                return parseFabricVersionRange(mc.getAsString());
            } else if (mc.isJsonArray()) {
                JsonArray arr = mc.getAsJsonArray();
                if (!arr.isEmpty()) {
                    return parseFabricVersionRange(arr.get(0).getAsString());
                }
            }
        }

        return null;
    }

    /** Pulls the target version out of a Fabric range like ">=1.21", "~1.21.1", "1.21.x", ">=1.21 <1.22". */
    private String parseFabricVersionRange(String range) {
        String version = range
            .replaceAll("[>=<~^]", "")
            .replaceAll("x", "0")
            .trim();

        if (version.contains(" ")) {
            version = version.split(" ")[0];
        }

        return version;
    }

    private String extractFabricApiVersion(JsonObject json) {
        if (!json.has("depends")) return null;

        JsonObject depends = json.getAsJsonObject("depends");

        if (depends.has("fabricloader")) {
            JsonElement loader = depends.get("fabricloader");
            if (loader.isJsonPrimitive()) {
                return parseFabricVersionRange(loader.getAsString());
            }
        }

        if (depends.has("fabric-api")) {
            JsonElement api = depends.get("fabric-api");
            if (api.isJsonPrimitive()) {
                return parseFabricVersionRange(api.getAsString());
            }
        }

        return null;
    }

    private ModVersionInfo parseForgeMod(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream is = jar.getInputStream(entry);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            StringBuilder fullContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                fullContent.append(line).append("\n");
            }
            String tomlContent = fullContent.toString();

            Map<String, String> toml = parseSimpleToml(
                new BufferedReader(new java.io.StringReader(tomlContent)));

            String modId = extractPrimaryModValue(tomlContent, "modId",
                    toml.getOrDefault("modId", "unknown"));
            String modVersion = extractPrimaryModValue(tomlContent, "version",
                    toml.getOrDefault("version", "unknown"));
            String mcVersion = extractMcVersionFromToml(tomlContent);
            if (mcVersion == null) {
                mcVersion = inferMcVersionFromModVersion(modVersion);
            }
            String forgeVersion = toml.get("forge");

            Set<String> packages = scanModPackages(jar, modId);
            Set<String> apiDeps = scanApiDependencies(jar);

            return new ModVersionInfo(
                modId,
                modVersion,
                mcVersion,
                "forge",
                forgeVersion,
                packages,
                apiDeps,
                checkForRemovedApis(apiDeps)
            );
        }
    }
    
    private ModVersionInfo parseLegacyForgeMod(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream is = jar.getInputStream(entry);
             Reader reader = new InputStreamReader(is)) {

            JsonArray array = GSON.fromJson(reader, JsonArray.class);
            if (array.isEmpty()) return null;

            JsonObject first = array.get(0).getAsJsonObject();

            String modId = first.has("modid") ? first.get("modid").getAsString() : "unknown";
            String modVersion = first.has("version") ? first.get("version").getAsString() : "unknown";
            String mcVersion = first.has("mcversion") ? first.get("mcversion").getAsString() : null;
            
            Set<String> packages = scanModPackages(jar, modId);
            Set<String> apiDeps = scanApiDependencies(jar);
            
            return new ModVersionInfo(
                modId,
                modVersion,
                mcVersion,
                "forge",
                null,
                packages,
                apiDeps,
                checkForRemovedApis(apiDeps)
            );
        }
    }
    
    private ModVersionInfo parseNeoForgeMod(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream is = jar.getInputStream(entry);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            StringBuilder fullContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                fullContent.append(line).append("\n");
            }
            String tomlContent = fullContent.toString();

            Map<String, String> toml = parseSimpleToml(
                new BufferedReader(new java.io.StringReader(tomlContent)));

            String modId = extractPrimaryModValue(tomlContent, "modId",
                    toml.getOrDefault("modId", "unknown"));
            String modVersion = extractPrimaryModValue(tomlContent, "version",
                    toml.getOrDefault("version", "unknown"));
            String mcVersion = extractMcVersionFromToml(tomlContent);
            if (mcVersion == null) {
                mcVersion = inferMcVersionFromModVersion(modVersion);
            }
            String neoforgeVersion = toml.get("neoforge");

            Set<String> packages = scanModPackages(jar, modId);
            Set<String> apiDeps = scanApiDependencies(jar);

            return new ModVersionInfo(
                modId,
                modVersion,
                mcVersion,
                "neoforge",
                neoforgeVersion,
                packages,
                apiDeps,
                checkForRemovedApis(apiDeps)
            );
        }
    }

    /** Reads the minecraft versionRange out of the [[dependencies.xxx]] block whose modId is "minecraft". */
    private String extractMcVersionFromToml(String tomlContent) {
        String[] blocks = tomlContent.split("(?=\\[\\[dependencies\\.)");

        for (String block : blocks) {
            if (block.contains("modId") && block.contains("minecraft")) {
                java.util.regex.Matcher idMatcher = java.util.regex.Pattern
                    .compile("modId\\s*=\\s*\"minecraft\"")
                    .matcher(block);

                if (idMatcher.find()) {
                    java.util.regex.Matcher rangeMatcher = java.util.regex.Pattern
                        .compile("versionRange\\s*=\\s*\"([^\"]+)\"")
                        .matcher(block);

                    if (rangeMatcher.find()) {
                        return parseMavenVersionRange(rangeMatcher.group(1));
                    }
                }
            }
        }

        return null;
    }

    /**
     * Reads a value from the first [[mods]] block. The simple TOML reader deliberately cannot
     * model arrays of tables, so allowing later dependency blocks to overwrite modId made old
     * Forge mods appear to be their final dependency instead of the mod itself.
     */
    private String extractPrimaryModValue(String tomlContent, String key, String fallback) {
        java.util.regex.Matcher blockMatcher = java.util.regex.Pattern
                .compile("(?ms)^\\s*\\[\\[mods]]\\s*$.*?(?=^\\s*\\[\\[|\\z)")
                .matcher(tomlContent);
        if (!blockMatcher.find()) {
            return fallback;
        }
        java.util.regex.Matcher valueMatcher = java.util.regex.Pattern
                .compile("(?m)^\\s*" + java.util.regex.Pattern.quote(key)
                        + "\\s*=\\s*[\"']([^\"']+)[\"']")
                .matcher(blockMatcher.group());
        return valueMatcher.find() ? valueMatcher.group(1).trim() : fallback;
    }

    /**
     * Old Forge metadata sometimes omits the minecraft dependency entirely. Accept the common
     * version shape where the mod version starts with a supported Minecraft version followed by
     * a separator, for example 1.16.4-0.3.9. Plain mod semver such as 1.3.0 is not inferred.
     */
    private String inferMcVersionFromModVersion(String modVersion) {
        if (modVersion == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(?:mc)?((?:1\\.(?:1[2-9]|2[0-1])(?:\\.\\d+)?|26\\.\\d+(?:\\.\\d+)?))(?=[+_-])",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(modVersion.trim());
        if (!matcher.find()) {
            return null;
        }
        String inferred = matcher.group(1);
        LOGGER.info("Inferred Minecraft {} from mod version {} because its metadata has no "
                + "minecraft dependency", inferred, modVersion);
        return inferred;
    }

    /**
     * Resolve a Maven version range to the mod's effective source MC version.
     *
     * <p>Host containment wins (#174): a FINITE range containing the host, e.g.
     * {@code [1.19,1.20.1]} on a 1.20.1 host, means the mod runs natively there. Taking its
     * lower bound made Retromod transform a working multi-version mod as "a 1.19 mod" and
     * break it. Such a range now reads as host-version, so {@code needsTransformation} skips
     * the mod. Open ranges like {@code [1.19,)} keep the old lower-bound behavior: on a 26.x
     * host that 1.19 mod genuinely needs translation.
     *
     * <p>Otherwise: the lower bound: "[1.21,1.21.1)" -> "1.21", "[1.21.8,)" -> "1.21.8".
     */
    private String parseMavenVersionRange(String range) {
        String trimmed = range.trim();
        String clean = trimmed.replaceAll("[\\[\\]()\\s]", "");
        String[] parts = clean.split(",", -1);
        String lower = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : null;

        String host = RetromodVersion.TARGET_MC_VERSION;
        if (host != null && lower != null && parts.length >= 2 && !parts[1].isEmpty()) {
            String upper = parts[1];
            try {
                boolean lowerOk = trimmed.startsWith("(")
                        ? RetromodVersion.compareMcVersions(lower, host) < 0
                        : RetromodVersion.compareMcVersions(lower, host) <= 0;
                boolean upperOk = trimmed.endsWith(")")
                        ? RetromodVersion.compareMcVersions(host, upper) < 0
                        : RetromodVersion.compareMcVersions(host, upper) <= 0;
                if (lowerOk && upperOk) {
                    LOGGER.info("Mod range {} includes host {}; treating it as "
                            + "native, no transform (#174)", range, host);
                    return host;
                }
            } catch (Exception e) {
                // unparseable bound: fall through to the lower-bound behavior
            }
        }
        return lower;
    }

    private Map<String, String> parseSimpleToml(BufferedReader reader) throws IOException {
        Map<String, String> result = new HashMap<>();
        String line;
        String currentSection = "";
        boolean inMultiLineString = false;
        StringBuilder multiLineValue = new StringBuilder();
        String multiLineKey = "";

        while ((line = reader.readLine()) != null) {
            // triple-quoted '''...''' multi-line string
            if (inMultiLineString) {
                if (line.contains("'''")) {
                    multiLineValue.append(line, 0, line.indexOf("'''"));
                    result.put(multiLineKey, multiLineValue.toString().trim());
                    if (!currentSection.isEmpty()) {
                        result.put(currentSection + "." + multiLineKey, multiLineValue.toString().trim());
                    }
                    inMultiLineString = false;
                } else {
                    multiLineValue.append(line).append("\n");
                }
                continue;
            }

            line = line.trim();

            if (line.isEmpty() || line.startsWith("#")) continue;

            if (line.startsWith("[[") && line.endsWith("]]")) {
                currentSection = line.substring(2, line.length() - 2).trim();
                continue;
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).trim();
                continue;
            }

            if (line.contains("=")) {
                String[] parts = line.split("=", 2);
                String key = parts[0].trim();
                String value = parts[1].trim();

                if (value.equals("'''") || value.startsWith("'''")) {
                    inMultiLineString = true;
                    multiLineKey = key;
                    multiLineValue = new StringBuilder();
                    String after = value.substring(3);
                    if (after.contains("'''")) {
                        result.put(key, after.substring(0, after.indexOf("'''")).trim());
                        if (!currentSection.isEmpty()) {
                            result.put(currentSection + "." + key, after.substring(0, after.indexOf("'''")).trim());
                        }
                        inMultiLineString = false;
                    } else {
                        multiLineValue.append(after).append("\n");
                    }
                    continue;
                }

                value = value
                    .replaceAll("^\"|\"$", "")
                    .replaceAll("^'|'$", "")
                    .replaceAll("^\\[|\\]$", ""); // drop version-range brackets like [1.21,)

                result.put(key, value);

                if (!currentSection.isEmpty()) {
                    result.put(currentSection + "." + key, value);
                }
            }
        }

        return result;
    }

    /** Packages that hold the mod's own classes, with common libraries filtered out. */
    private Set<String> scanModPackages(JarFile jar, String modId) {
        Set<String> packages = new HashSet<>();

        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();

            if (name.endsWith(".class") && !name.startsWith("META-INF")) {
                int lastSlash = name.lastIndexOf('/');
                if (lastSlash > 0) {
                    packages.add(name.substring(0, lastSlash + 1));
                }
            }
        }

        packages.removeIf(pkg ->
            pkg.startsWith("org/apache/") ||
            pkg.startsWith("com/google/") ||
            pkg.startsWith("org/slf4j/") ||
            pkg.startsWith("kotlin/") ||
            pkg.startsWith("org/objectweb/")
        );

        return packages;
    }

    /** Loader API package references found in the mod's class bytes (constant-pool substring scan). */
    private Set<String> scanApiDependencies(JarFile jar) {
        Set<String> apis = new HashSet<>();

        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();

            if (entry.getName().endsWith(".class")) {
                try (InputStream is = jar.getInputStream(entry)) {
                    byte[] bytes = is.readAllBytes();
                    String content = new String(bytes, "ISO-8859-1");

                    if (content.contains("net/fabricmc/")) {
                        apis.add("fabric-api");
                    }
                    if (content.contains("net/minecraftforge/")) {
                        apis.add("forge");
                    }
                    if (content.contains("net/neoforged/")) {
                        apis.add("neoforge");
                    }
                } catch (Exception e) {
                    // ignore unreadable entries
                }
            }
        }

        return apis;
    }

    // TODO: check apiDeps against a removed-API registry; stubbed to false for now.
    private boolean checkForRemovedApis(Set<String> apiDeps) {
        return false;
    }
}
