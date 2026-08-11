/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Looks up Modrinth for a native build of a mod that already targets the host
 * MC version, so the user can grab that instead of transforming.
 */
public class ModrinthVersionChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Modrinth");

    private static final String MODRINTH_API = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "Retromod/" + RetromodVersion.RETROMOD_VERSION
            + " (bownlux)";
    private static final long MAX_METADATA_BYTES = 4L * 1024 * 1024;
    private static final long MAX_ERROR_BYTES = 64L * 1024;

    private static final java.util.Map<String, ModrinthResult> cache = new java.util.concurrent.ConcurrentHashMap<>();
    
    public record ModrinthResult(
        boolean found,
        String projectId,
        String projectSlug,
        String projectName,
        String versionId,
        String versionNumber,
        String downloadUrl,
        String pageUrl
    ) {
        public static ModrinthResult notFound() {
            return new ModrinthResult(false, null, null, null, null, null, null, null);
        }
    }
    
    /**
     * Looks for a native build of the mod for {@code targetMcVersion}.
     * Gated on the {@code check_for_native_versions} config flag (default off):
     * when it's off this returns {@link ModrinthResult#notFound()} with no HTTP.
     *
     * @param modJarPath Path to the mod JAR
     * @param targetMcVersion Target Minecraft version
     * @return ModrinthResult, or notFound()
     */
    public static ModrinthResult checkForNativeVersion(Path modJarPath, String targetMcVersion) {
        if (!isUserOptedIn()) {
            return ModrinthResult.notFound();
        }
        try {
            ModInfo info = extractModInfo(modJarPath);
            if (info == null || info.modId == null) {
                return ModrinthResult.notFound();
            }

            String cacheKey = info.modId + ":" + targetMcVersion;
            if (cache.containsKey(cacheKey)) {
                return cache.get(cacheKey);
            }

            ModrinthResult result = searchModrinth(info, targetMcVersion);
            cache.put(cacheKey, result);

            return result;

        } catch (Exception e) {
            LOGGER.debug("Error checking Modrinth: {}", e.getMessage());
            return ModrinthResult.notFound();
        }
    }

    /**
     * True only if the user opted in via {@code check_for_native_versions=true}
     * in {@code config/retromod/config.json}, or the
     * {@code -Dretromod.checkForNativeVersions=true} system property. Default
     * off, since any outbound network call must be explicitly enabled.
     */
    private static boolean isUserOptedIn() {
        if (Boolean.getBoolean("retromod.checkForNativeVersions")) {
            return true;
        }
        // Read the config directly to avoid a circular dependency on Retromod.java.
        try {
            Path cfg = Path.of("config/retromod/config.json");
            if (!java.nio.file.Files.exists(cfg)) return false;
            String json = java.nio.file.Files.readString(cfg);
            var obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            return obj.has("check_for_native_versions")
                && obj.get("check_for_native_versions").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static ModInfo extractModInfo(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry fabricEntry = jar.getEntry("fabric.mod.json");
            if (fabricEntry != null) {
                String content;
                try (var input = jar.getInputStream(fabricEntry)) {
                    content = new String(com.retromod.util.ZipSecurity.safeReadAllBytes(
                            input, MAX_METADATA_BYTES), StandardCharsets.UTF_8);
                }
                return parseModJson(content, "fabric");
            }

            ZipEntry forgeEntry = jar.getEntry("META-INF/mods.toml");
            if (forgeEntry == null) {
                forgeEntry = jar.getEntry("META-INF/neoforge.mods.toml");
            }
            if (forgeEntry != null) {
                String content;
                try (var input = jar.getInputStream(forgeEntry)) {
                    content = new String(com.retromod.util.ZipSecurity.safeReadAllBytes(
                            input, MAX_METADATA_BYTES), StandardCharsets.UTF_8);
                }
                return parseModsToml(content);
            }

            ZipEntry oldForgeEntry = jar.getEntry("mcmod.info");
            if (oldForgeEntry != null) {
                String content;
                try (var input = jar.getInputStream(oldForgeEntry)) {
                    content = new String(com.retromod.util.ZipSecurity.safeReadAllBytes(
                            input, MAX_METADATA_BYTES), StandardCharsets.UTF_8);
                }
                return parseMcmodInfo(content);
            }

        } catch (Exception e) {
            LOGGER.debug("Could not extract mod info from: {}", jarPath.getFileName());
        }

        return null;
    }

    private static ModInfo parseModJson(String json, String loader) {
        String modId = extractJsonField(json, "id");
        String modName = extractJsonField(json, "name");
        return new ModInfo(modId, modName, loader);
    }

    private static ModInfo parseModsToml(String toml) {
        Pattern idPattern = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"");
        Pattern namePattern = Pattern.compile("displayName\\s*=\\s*\"([^\"]+)\"");
        
        Matcher idMatcher = idPattern.matcher(toml);
        Matcher nameMatcher = namePattern.matcher(toml);

        String modId = idMatcher.find() ? idMatcher.group(1) : null;
        String modName = nameMatcher.find() ? nameMatcher.group(1) : null;

        return new ModInfo(modId, modName, "forge");
    }

    private static ModInfo parseMcmodInfo(String json) {
        String modId = extractJsonField(json, "modid");
        String modName = extractJsonField(json, "name");
        return new ModInfo(modId, modName, "forge");
    }

    private static String extractJsonField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static ModrinthResult searchModrinth(ModInfo info, String targetMcVersion) {
        try {
            // Look up by slug (mod ID); fall back to a name search.
            String projectData = fetchUrl(MODRINTH_API + "/project/" + encodePathSegment(info.modId));

            if (projectData == null && info.modName != null) {
                String searchQuery = URLEncoder.encode(info.modName, StandardCharsets.UTF_8);
                String searchData = fetchUrl(MODRINTH_API + "/search?query=" + searchQuery + "&limit=1");

                if (searchData != null) {
                    String slug = extractJsonField(searchData, "slug");
                    if (slug != null) {
                        projectData = fetchUrl(MODRINTH_API + "/project/" + encodePathSegment(slug));
                    }
                }
            }

            if (projectData == null) {
                return ModrinthResult.notFound();
            }

            String projectId = extractJsonField(projectData, "id");
            String projectSlug = extractJsonField(projectData, "slug");
            String projectName = extractJsonField(projectData, "title");

            if (projectId == null) {
                return ModrinthResult.notFound();
            }

            String gameVersions = URLEncoder.encode("[\"" + targetMcVersion + "\"]",
                    StandardCharsets.UTF_8);
            String versionsUrl = MODRINTH_API + "/project/" + encodePathSegment(projectId)
                    + "/version?game_versions=" + gameVersions;
            String versionsData = fetchUrl(versionsUrl);

            if (versionsData == null || versionsData.equals("[]")) {
                return ModrinthResult.notFound();
            }

            // First entry is the latest.
            String versionId = extractJsonField(versionsData, "id");
            String versionNumber = extractJsonField(versionsData, "version_number");

            String pageUrl = "https://modrinth.com/mod/" + projectSlug;
            String downloadUrl = "https://modrinth.com/mod/" + projectSlug + "/version/" + versionId;

            LOGGER.info("Found native version of {} for {}: {}", projectName, targetMcVersion, versionNumber);

            return new ModrinthResult(
                true,
                projectId,
                projectSlug,
                projectName,
                versionId,
                versionNumber,
                downloadUrl,
                pageUrl
            );

        } catch (Exception e) {
            LOGGER.debug("Modrinth search failed: {}", e.getMessage());
            return ModrinthResult.notFound();
        }
    }

    private static String fetchUrl(String urlString) {
        HttpURLConnection conn = null;
        try {
            URI uri = URI.create(urlString);
            if (!isAllowedApiUri(uri)) return null;
            URL url = uri.toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(false);

            if (conn.getResponseCode() != 200) {
                try (var es = conn.getErrorStream()) {
                    if (es != null) {
                        com.retromod.util.ZipSecurity.safeReadAllBytes(es, MAX_ERROR_BYTES);
                    }
                }
                return null;
            }

            try (var input = conn.getInputStream()) {
                return new String(com.retromod.util.ZipSecurity.safeReadAllBytes(
                        input, MAX_METADATA_BYTES), StandardCharsets.UTF_8);
            }

        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static boolean isAllowedApiUri(URI uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && "api.modrinth.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Shows the download offer dialog. Returns true if the user chose to skip
     * transformation.
     */
    public static boolean offerNativeVersion(ModrinthResult result, String modFileName) {
        if (!result.found() || !EnvironmentDetector.canShowGui()) {
            return false;
        }
        
        String message = String.format("""
            %s has a release made for this Minecraft version.

            Current file: %s
            Available version: %s

            Using the native release is usually more reliable than transforming
            an older one.
            """,
            result.projectName(),
            modFileName,
            result.versionNumber()
        );
        
        int choice = JOptionPane.showOptionDialog(
            null,
            message,
            "A native mod version is available",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.INFORMATION_MESSAGE,
            null,
            new String[]{"Open Modrinth", "Update This Copy", "Skip This Mod"},
            "Open Modrinth"
        );
        
        if (choice == 0) {
            openBrowser(result.pageUrl());
            return true; // skip transformation
        } else if (choice == 2) {
            return true;
        }

        return false;
    }

    /**
     * Opens the URL in the default browser. When the Desktop API is
     * unavailable, falls back to a copy-the-URL dialog rather than shelling
     * out, which keeps Retromod free of {@code Runtime.exec} calls.
     */
    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception e) {
            LOGGER.warn("Could not open browser: {}", e.getMessage());
        }
        JOptionPane.showMessageDialog(
            null,
            "Please open this URL in your browser:\n\n" + url,
            "Open Modrinth",
            JOptionPane.INFORMATION_MESSAGE
        );
    }
    
    /** Logs the native-version notice to the console (server mode). */
    public static void logNativeVersionAvailable(ModrinthResult result, String modFileName) {
        if (!result.found()) return;
        
        LOGGER.warn("{} ({}) has a native release, version {}: {}",
            result.projectName(), modFileName, result.versionNumber(), result.pageUrl());
    }
    
    private record ModInfo(String modId, String modName, String loader) {}
}
