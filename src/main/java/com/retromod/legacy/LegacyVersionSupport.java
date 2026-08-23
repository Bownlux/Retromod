/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacy;

import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.jar.*;
import java.util.regex.*;

/**
 * Legacy Version Support: detects mod eras and manages compatibility config.
 * 
 * Support Levels:
 * - FULL: Fabric 1.14+, Forge 1.13+, NeoForge 1.20.2+
 * - EXPERIMENTAL: Forge/Fabric 1.8-1.12.2 (enable in config)
 * - LIMITED: Forge/Fabric 1.6-1.7.10 (enable in config)
 * - NONE: Classic Forge 1.1-1.5.2 (too different)
 */
public class LegacyVersionSupport {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final long MAX_METADATA_SIZE = 2L * 1024 * 1024;
    
    public enum ModLoaderEra {
        CLASSIC_FORGE(false),    // 1.1-1.5.2: NOT supported
        LEGACY_FORGE(true),      // 1.6-1.7.10: limited
        OLD_FORGE(true),         // 1.8-1.12.2: experimental
        MODERN_FORGE(true),      // 1.13-1.20.x: full
        CURRENT_FORGE(true),     // 1.21+: native
        LEGACY_FABRIC_OLD(true), // 1.3.2-1.7.10: limited
        LEGACY_FABRIC(true),     // 1.8-1.13.2: experimental
        MODERN_FABRIC(true),     // 1.14+: full
        NEOFORGE(true);          // 1.20.2+: full
        
        public final boolean canSupport;
        ModLoaderEra(boolean canSupport) { this.canSupport = canSupport; }
    }
    
    private boolean experimentalSupport = false;
    private boolean limitedSupport = false;
    
    public LegacyVersionSupport() {
        loadConfig();
    }
    
    private void loadConfig() {
        Path configPath = Path.of("config/retromod/legacy.properties");
        if (Files.exists(configPath)) {
            try (var is = new FileInputStream(configPath.toFile())) {
                Properties props = new Properties();
                props.load(is);
                experimentalSupport = Boolean.parseBoolean(props.getProperty("experimental_support", "false"));
                limitedSupport = Boolean.parseBoolean(props.getProperty("limited_support", "false"));
            } catch (Exception e) { /* use defaults */ }
        } else {
            createDefaultConfig(configPath);
        }
    }
    
    private void createDefaultConfig(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, """
                # Retromod Legacy Support
                # experimental_support: Enable Forge/Fabric 1.8-1.12.2 (most work)
                experimental_support=false
                # limited_support: Enable Forge/Fabric 1.6-1.7.10 (many issues)
                limited_support=false
                # Classic Forge 1.1-1.5.2 cannot be supported (too different)
                """);
        } catch (Exception e) { /* ignore */ }
    }
    
    /** Detect which era a mod is from. */
    public ModLoaderEra detectModEra(Path jarPath) {
        try {
            validateArchiveInput(jarPath);
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                // Fabric
                if (jar.getEntry("fabric.mod.json") != null) {
                    String mcVer = extractVersion(jar, "fabric.mod.json", "\"minecraft\"\\s*:\\s*\"([^\"]+)\"");
                    return getFabricEra(mcVer);
                }
                // NeoForge
                if (jar.getEntry("META-INF/neoforge.mods.toml") != null) return ModLoaderEra.NEOFORGE;
                // Modern Forge
                if (jar.getEntry("META-INF/mods.toml") != null) {
                    String mcVer = extractVersion(jar, "META-INF/mods.toml", "versionRange\\s*=\\s*\"\\[([0-9.]+)");
                    return getForgeEra(mcVer);
                }
                // Old Forge
                if (jar.getEntry("mcmod.info") != null) {
                    String mcVer = extractVersion(jar, "mcmod.info", "\"mcversion\"\\s*:\\s*\"([^\"]+)\"");
                    return getForgeEra(mcVer);
                }
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }
    
    static String extractVersion(JarFile jar, String entry, String pattern) throws IOException {
        var ze = jar.getEntry(entry);
        if (ze == null) return null;
        String content;
        try (InputStream input = jar.getInputStream(ze)) {
            content = new String(ZipSecurity.safeReadAllBytes(input, MAX_METADATA_SIZE),
                    StandardCharsets.UTF_8);
        }
        Matcher matcher = Pattern.compile(pattern).matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void validateArchiveInput(Path jarPath) throws IOException {
        if (!Files.isRegularFile(jarPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Mod input is not a regular file: " + jarPath);
        }
    }
    
    private ModLoaderEra getFabricEra(String mcVer) {
        if (mcVer == null) return ModLoaderEra.MODERN_FABRIC;
        int[] v = parseVersion(mcVer);
        if (v[0] == 1) {
            if (v[1] >= 14) return ModLoaderEra.MODERN_FABRIC;
            if (v[1] >= 8) return ModLoaderEra.LEGACY_FABRIC;
            return ModLoaderEra.LEGACY_FABRIC_OLD;
        }
        return ModLoaderEra.MODERN_FABRIC;
    }
    
    private ModLoaderEra getForgeEra(String mcVer) {
        if (mcVer == null) return ModLoaderEra.MODERN_FORGE;
        int[] v = parseVersion(mcVer);
        if (v[0] == 1) {
            if (v[1] >= 21) return ModLoaderEra.CURRENT_FORGE;
            if (v[1] >= 13) return ModLoaderEra.MODERN_FORGE;
            if (v[1] >= 8) return ModLoaderEra.OLD_FORGE;
            if (v[1] >= 6) return ModLoaderEra.LEGACY_FORGE;
            return ModLoaderEra.CLASSIC_FORGE;
        }
        return ModLoaderEra.CURRENT_FORGE;
    }
    
    private int[] parseVersion(String ver) {
        try {
            String[] p = ver.replaceAll("[^0-9.]", "").split("\\.");
            return new int[]{
                p.length > 0 ? Integer.parseInt(p[0]) : 1,
                p.length > 1 ? Integer.parseInt(p[1]) : 0
            };
        } catch (Exception e) { return new int[]{1, 0}; }
    }
    
    /** Check if mod era is supported with current config. */
    public boolean isSupported(ModLoaderEra era) {
        if (era == null || era == ModLoaderEra.CLASSIC_FORGE) return false;
        if (era == ModLoaderEra.LEGACY_FORGE || era == ModLoaderEra.LEGACY_FABRIC_OLD) return limitedSupport;
        if (era == ModLoaderEra.OLD_FORGE || era == ModLoaderEra.LEGACY_FABRIC) return experimentalSupport;
        return true;
    }
    
    /** Log support status (only if experimental/limited enabled). */
    public void logSupportStatus(Path modsFolder) {
        if (experimentalSupport || limitedSupport) {
            LOGGER.info("Legacy support: experimental={}, limited={}", experimentalSupport, limitedSupport);
        }
    }
    
    public boolean isExperimentalEnabled() { return experimentalSupport; }
    public boolean isLimitedEnabled() { return limitedSupport; }
}
