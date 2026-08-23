/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.util.ZipSecurity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Resolves cross-mod dependencies where old mods depend on new/native mods.
 */
public class CrossModDependencyResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Dependencies");
    private static final long MAX_METADATA_SIZE = 2L * 1024 * 1024;
    private static final Pattern FORGE_MOD_ID = Pattern.compile(
            "^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern FORGE_DEPENDENCY_HEADER = Pattern.compile(
            "^\\s*\\[\\[dependencies\\.([^]]+)]]\\s*(?:#.*)?$");
    private static final Pattern TOML_TABLE_HEADER = Pattern.compile(
            "^\\s*\\[.*]\\s*(?:#.*)?$");

    // modId -> dependency modIds
    private final Map<String, List<String>> dependencyGraph = new ConcurrentHashMap<>();

    // mods already built for the current MC version
    private final Set<String> nativeMods = ConcurrentHashMap.newKeySet();

    private final String targetMcVersion;
    
    public CrossModDependencyResolver(String targetMcVersion) {
        this.targetMcVersion = targetMcVersion;
    }

    /** Scans all mods in parallel and builds the dependency graph. */
    public void scanMods(Path modsFolder) {
        if (!Files.exists(modsFolder)) return;

        try (var stream = Files.list(modsFolder)) {
            List<Path> jars = stream.filter(p -> p.toString().endsWith(".jar")).toList();
            if (jars.isEmpty()) return;

            int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> futures = new ArrayList<>(jars.size());
                for (Path jar : jars) {
                    futures.add(executor.submit(() -> scanMod(jar)));
                }

                for (Future<?> f : futures) {
                    try { f.get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
                }
            } finally {
                executor.shutdownNow();
            }

        } catch (Exception e) {
            LOGGER.debug("Could not scan mods: {}", e.getMessage());
        }

        if (!dependencyGraph.isEmpty()) {
            int totalDeps = dependencyGraph.values().stream().mapToInt(List::size).sum();
            LOGGER.info("Found {} mods with {} dependencies", dependencyGraph.size(), totalDeps);
        }
    }

    private void scanMod(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry fabricJson = jar.getEntry("fabric.mod.json");
            if (fabricJson != null) {
                scanFabricMod(jar, fabricJson);
                return;
            }

            ZipEntry modsToml = jar.getEntry("META-INF/mods.toml");
            if (modsToml == null) modsToml = jar.getEntry("META-INF/neoforge.mods.toml");
            if (modsToml != null) {
                scanForgeMod(jar, modsToml);
            }
        } catch (Exception e) {
            // not all jars are mods
        }
    }

    private void scanFabricMod(JarFile jar, ZipEntry entry) throws IOException {
        String content = readMetadata(jar, entry);

        String modId = extractJsonValue(content, "id");
        if (modId == null) return;

        String mcVersion = extractJsonValue(content, "minecraft");
        if (mcVersion != null && isNativeVersion(mcVersion)) {
            nativeMods.add(modId);
        }

        List<String> deps = new ArrayList<>();

        Pattern depsPattern = Pattern.compile("\"depends\"\\s*:\\s*\\{([^}]+)\\}");
        Matcher depsMatcher = depsPattern.matcher(content);
        if (depsMatcher.find()) {
            String depsContent = depsMatcher.group(1);
            Pattern depPattern = Pattern.compile("\"([^\"]+)\"\\s*:");
            Matcher depMatcher = depPattern.matcher(depsContent);
            while (depMatcher.find()) {
                String dep = depMatcher.group(1);
                if (!dep.equals("minecraft") && !dep.equals("fabricloader") &&
                    !dep.equals("fabric-api") && !dep.equals("java")) {
                    deps.add(dep);
                }
            }
        }

        if (!deps.isEmpty()) {
            dependencyGraph.put(modId, deps);
        }
    }

    private void scanForgeMod(JarFile jar, ZipEntry entry) throws IOException {
        String content = readMetadata(jar, entry);

        Matcher modIdMatcher = Pattern.compile(
                "(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(content);
        if (!modIdMatcher.find()) return;
        String modId = modIdMatcher.group(1);

        List<String> deps = extractForgeDependencies(content, modId);

        if (!deps.isEmpty()) {
            dependencyGraph.put(modId, deps);
        }
    }

    /** Parses dependency tables without inserting an archive-controlled mod ID into a regex. */
    static List<String> extractForgeDependencies(String content, String modId) {
        if (content == null || modId == null) {
            return List.of();
        }

        Set<String> dependencies = new LinkedHashSet<>();
        boolean matchingTable = false;
        for (String line : content.lines().toList()) {
            Matcher header = FORGE_DEPENDENCY_HEADER.matcher(line);
            if (header.matches()) {
                String owner = stripTomlKeyQuotes(header.group(1).trim());
                matchingTable = modId.equals(owner);
                continue;
            }
            if (TOML_TABLE_HEADER.matcher(line).matches()) {
                matchingTable = false;
                continue;
            }
            if (!matchingTable) {
                continue;
            }
            Matcher dependency = FORGE_MOD_ID.matcher(line);
            if (!dependency.find()) {
                continue;
            }
            String dependencyId = dependency.group(1);
            if (!dependencyId.equals("minecraft")
                    && !dependencyId.equals("forge")
                    && !dependencyId.equals("neoforge")) {
                dependencies.add(dependencyId);
            }
        }
        return List.copyOf(dependencies);
    }

    private static String stripTomlKeyQuotes(String key) {
        if (key.length() >= 2) {
            char first = key.charAt(0);
            char last = key.charAt(key.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return key.substring(1, key.length() - 1);
            }
        }
        return key;
    }

    private String readMetadata(JarFile jar, ZipEntry entry) throws IOException {
        try (InputStream input = jar.getInputStream(entry)) {
            return new String(ZipSecurity.safeReadAllBytes(input, MAX_METADATA_SIZE),
                    StandardCharsets.UTF_8);
        }
    }

    private boolean isNativeVersion(String version) {
        if (version == null) return false;
        return version.contains(targetMcVersion) ||
               version.replace(">=", "").replace("~", "").trim().equals(targetMcVersion);
    }

    public List<String> getDependencies(String modId) {
        return dependencyGraph.getOrDefault(modId, Collections.emptyList());
    }

    /** Whether a mod already targets the current MC version and needs no transform. */
    public boolean isNativeMod(String modId) {
        return nativeMods.contains(modId);
    }

    public void logResolutionInfo() {
        if (dependencyGraph.isEmpty()) return;

        LOGGER.info("Cross-mod dependencies detected:");
        for (var entry : dependencyGraph.entrySet()) {
            String status = nativeMods.contains(entry.getKey()) ? "native" : "transform";
            LOGGER.info("  {} [{}] → {}", entry.getKey(), status, entry.getValue());
        }
    }

    private String extractJsonValue(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx == -1) return null;
        int start = json.indexOf("\"", idx + key.length() + 2) + 1;
        int end = json.indexOf("\"", start);
        return (start > 0 && end > start) ? json.substring(start, end) : null;
    }
}
