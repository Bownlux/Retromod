/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.embedder;

import java.util.Set;

/**
 * Information about a mod's version and compatibility requirements.
 */
public record ModVersionInfo(
    String modId,
    String modVersion,
    String targetMcVersion,
    String modLoaderType,      // "fabric", "quilt", "forge", "neoforge"
    String modLoaderVersion,
    Set<String> modPackages,   // Java packages containing mod code
    Set<String> apiDependencies,
    boolean usesRemovedApis
) {
    /**
     * Whether this mod needs transformation to run on the target version.
     *
     * <p>Comparison precision follows what the mod declared. A specific patch target
     * ({@code "1.21.1"}) is compared in full, so a 1.21.1 mod on a 1.21.11 host is
     * translated. A bare major.minor floor ({@code "1.20"}, from a Forge range like
     * {@code [1.20,)}) is compared minor-to-minor, so a 1.20.x mod on a 1.20.1 host
     * is left alone (#84). A null or unparseable target is left alone, not guessed.
     */
    public boolean needsTransformation(String currentMcVersion) {
        if (targetMcVersion == null || currentMcVersion == null
                || targetMcVersion.equals(currentMcVersion)) {
            return false;
        }

        int[] target = parseVersion(targetMcVersion);
        int[] current = parseVersion(currentMcVersion);
        if (target.length < 2 || current.length < 2) {
            return false; // unparseable, don't guess
        }

        // bare major.minor target: compare minor-to-minor so same-minor patches
        // don't trigger a needless transform. major.minor.patch compares in full.
        if (target.length == 2) {
            int[] currentMinor = {current[0], current[1]};
            return compareVersions(target, currentMinor) < 0;
        }
        return compareVersions(target, current) < 0;
    }
    
    private int[] parseVersion(String version) {
        // strip pre-release suffix ("1.0.0-beta.6" -> "1.0.0")
        int hyphen = version.indexOf('-');
        if (hyphen > 0) version = version.substring(0, hyphen);

        String[] parts = version.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }
    
    private int compareVersions(int[] a, int[] b) {
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int va = i < a.length ? a[i] : 0;
            int vb = i < b.length ? b[i] : 0;
            if (va != vb) return va - vb;
        }
        return 0;
    }
}
