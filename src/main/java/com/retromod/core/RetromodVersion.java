/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

/** Shares version state without linking one loader's entry point to another loader API. */
public final class RetromodVersion {

    /** Target MC version, auto-detected at boot by whichever loader entry point runs first. */
    public static volatile String TARGET_MC_VERSION = "1.21.4";

    /** Retromod version used by loader metadata and transform cache keys. */
    public static final String RETROMOD_VERSION = "1.3.0-snapshot.7";

    private RetromodVersion() {}

    private static volatile boolean bannerLogged = false;

    /** Leave one clear note in the log when Retromod can affect another mod's behavior. */
    public static void logPresenceBanner(org.slf4j.Logger logger) {
        if (bannerLogged) {
            return;
        }
        bannerLogged = true;
        logger.warn("Retromod is updating older mods at runtime.");
        logger.warn("If a mod breaks, retry without Retromod before reporting it to that mod's author.");
        logger.warn("Report Retromod problems at https://github.com/Bownlux/Retromod/issues");
    }

    /**
     * Returns whether the host uses Mojang names at runtime. Unknown versions keep the
     * established unobfuscated behavior instead of silently skipping required remaps.
     */
    public static boolean isUnobfuscatedTarget(String hostVersion) {
        if (hostVersion == null) return true;
        try {
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^(\\d+)").matcher(hostVersion.trim());
            if (!m.find()) return true;
            return Integer.parseInt(m.group(1)) >= 26;
        } catch (Exception e) {
            return true;
        }
    }

    /** Returns whether {@code a} is newer than {@code b}, without excluding unknown versions. */
    public static boolean mcVersionExceeds(String a, String b) {
        try {
            return compareMcVersions(a, b) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Numeric per-component MC version compare ({@code 1.21.8} vs {@code 26.1} is negative). */
    public static int compareMcVersions(String a, String b) {
        int[] pa = parseMcVersion(a);
        int[] pb = parseMcVersion(b);
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? pa[i] : 0;
            int y = i < pb.length ? pb[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    /**
     * Whether {@code a} and {@code b} share the same {@code major.minor} ({@code 1.21} and
     * {@code 1.21.1}). The in-place scan uses this to skip patch-only differences from the host:
     * those are usually drop-in compatible, and transforming them can break a working mod by
     * firing API shims it never needed (#60). Such a mod can still go in {@code retromod-input/}
     * to force a transform. Fewer than 2 components returns {@code false} so we don't skip a real
     * cross-version transform.
     */
    public static boolean sameMinorVersion(String a, String b) {
        int[] pa = parseMcVersion(a);
        int[] pb = parseMcVersion(b);
        if (pa.length < 2 || pb.length < 2) return false;
        return pa[0] == pb[0] && pa[1] == pb[1];
    }

    private static int[] parseMcVersion(String v) {
        if (v == null) return new int[0];
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)*)").matcher(v.trim());
        if (!m.find()) return new int[0];
        String[] parts = m.group(1).split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        return out;
    }

    /**
     * Read the host MC version from FancyModLoader across API generations, or {@code null} if none
     * work (the caller should log loudly rather than fall back to the default). Reflection-only, so
     * no FML class enters this class's linkage (#40). The accessor changed shape across generations;
     * tries, in order, FML 10.x {@code getCurrent().getVersionInfo()}, then static {@code versionInfo()},
     * then static {@code getVersionInfo()}.
     */
    public static String detectFmlMcVersion() {
        final Class<?> fmlLoader;
        try {
            fmlLoader = Class.forName("net.neoforged.fml.loading.FMLLoader");
        } catch (Throwable t) {
            return null;
        }
        // FML 10.x: instance API reached via the static getCurrent()
        try {
            Object current = fmlLoader.getMethod("getCurrent").invoke(null);
            if (current != null) {
                String mc = mcVersionOf(fmlLoader.getMethod("getVersionInfo").invoke(current));
                if (mc != null) return mc;
            }
        } catch (Throwable ignored) {}
        // Older FML: static versionInfo()
        try {
            String mc = mcVersionOf(fmlLoader.getMethod("versionInfo").invoke(null));
            if (mc != null) return mc;
        } catch (Throwable ignored) {}
        // Variant: static getVersionInfo()
        try {
            String mc = mcVersionOf(fmlLoader.getMethod("getVersionInfo").invoke(null));
            if (mc != null) return mc;
        } catch (Throwable ignored) {}
        return null;
    }

    /** Pull {@code mcVersion()} off a VersionInfo-like object, or {@code null}. */
    private static String mcVersionOf(Object versionInfo) {
        if (versionInfo == null) return null;
        try {
            Object mc = versionInfo.getClass().getMethod("mcVersion").invoke(versionInfo);
            if (mc instanceof String s && !s.isBlank()) return s;
        } catch (Throwable ignored) {}
        return null;
    }
}
