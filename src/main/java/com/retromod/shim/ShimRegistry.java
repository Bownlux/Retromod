/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim;

import com.retromod.core.AuxiliaryVersionShim;
import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.VersionShim;
import com.retromod.core.RetromodVersion;

import java.util.*;
import java.util.logging.Logger;

/**
 * Holds the loaded version shims and finds chains between versions. Mods targeting an
 * intermediate version (1.16.2) resolve to the nearest milestone shim (1.16.5) before
 * pathfinding. From 1.17 on every version has its own shim.
 */
public class ShimRegistry {

    private static final Logger LOGGER = Logger.getLogger(ShimRegistry.class.getName());

    /** Maps intermediate MC versions to the milestone shim that covers their release line. */
    private static final Map<String, String> VERSION_ALIASES;

    static {
        Map<String, String> aliases = new HashMap<>();

        aliases.put("1.13",   "1.13.2");
        aliases.put("1.13.0", "1.13.2");
        aliases.put("1.13.1", "1.13.2");

        aliases.put("1.14",   "1.14.4");
        aliases.put("1.14.0", "1.14.4");
        aliases.put("1.14.1", "1.14.4");
        aliases.put("1.14.2", "1.14.4");
        aliases.put("1.14.3", "1.14.4");

        aliases.put("1.15",   "1.15.2");
        aliases.put("1.15.0", "1.15.2");
        aliases.put("1.15.1", "1.15.2");

        aliases.put("1.16",   "1.16.5");
        aliases.put("1.16.0", "1.16.5");
        aliases.put("1.16.1", "1.16.5");
        aliases.put("1.16.2", "1.16.5");
        aliases.put("1.16.3", "1.16.5");
        aliases.put("1.16.4", "1.16.5");

        // Some mod metadata writers expand the first release in a line to x.y.0,
        // while Minecraft and the shim graph name those releases x.y.
        aliases.put("1.17.0", "1.17");
        aliases.put("1.18.0", "1.18");
        aliases.put("1.19.0", "1.19");
        aliases.put("1.20.0", "1.20");
        aliases.put("1.21.0", "1.21");

        // pre-releases / rc / snapshots → 26.1 (Fabric uses dots, Prism uses dashes)
        aliases.put("26.1-pre.1", "26.1");
        aliases.put("26.1-pre.2", "26.1");
        aliases.put("26.1-pre-1", "26.1");
        aliases.put("26.1-pre-2", "26.1");
        aliases.put("26.1 Pre-Release 1", "26.1");
        aliases.put("26.1 Pre-Release 2", "26.1");
        aliases.put("26.1-pre.3", "26.1");
        aliases.put("26.1-pre-3", "26.1");
        aliases.put("26.1 Pre-Release 3", "26.1");
        aliases.put("26.1-rc.1", "26.1");
        aliases.put("26.1-rc-1", "26.1");
        aliases.put("26.1 Release Candidate 1", "26.1");
        aliases.put("26.1-snapshot.1", "26.1");
        aliases.put("26.1-snapshot.2", "26.1");
        aliases.put("26.1-snapshot.3", "26.1");
        aliases.put("26.1.0", "26.1");
        aliases.put("26.1.1", "26.1");
        aliases.put("26.1.2", "26.1");

        for (int i = 1; i <= 6; i++) {
            aliases.put("26.2-pre." + i, "26.2");
            aliases.put("26.2-pre-" + i, "26.2");
            aliases.put("26.2 Pre-Release " + i, "26.2");
            aliases.put("26.2-rc." + i, "26.2");
            aliases.put("26.2-rc-" + i, "26.2");
            aliases.put("26.2 Release Candidate " + i, "26.2");
            aliases.put("26.2-snapshot." + i, "26.2");
            // Mojang's own ids use a hyphen, as in 26.2-snapshot-1.
            aliases.put("26.2-snapshot-" + i, "26.2");
        }
        aliases.put("26.2.0", "26.2");
        aliases.put("26.2.1", "26.2");
        aliases.put("26.2.2", "26.2");
        aliases.put("26.2.3", "26.2");

        // 26.3 is in snapshot. Mojang numbers these 26.3-snapshot-N and has reached 10, so the
        // range covers the rest of the cycle plus the pre-release and candidate spellings.
        for (int i = 1; i <= 20; i++) {
            aliases.put("26.3-snapshot." + i, "26.3");
            aliases.put("26.3-snapshot-" + i, "26.3");
            aliases.put("26.3-pre." + i, "26.3");
            aliases.put("26.3-pre-" + i, "26.3");
            aliases.put("26.3 Pre-Release " + i, "26.3");
            aliases.put("26.3-rc." + i, "26.3");
            aliases.put("26.3-rc-" + i, "26.3");
            aliases.put("26.3 Release Candidate " + i, "26.3");
        }
        aliases.put("26.3.0", "26.3");
        aliases.put("26.3.1", "26.3");
        aliases.put("26.3.2", "26.3");
        aliases.put("26.3.3", "26.3");

        VERSION_ALIASES = Collections.unmodifiableMap(aliases);
    }

    private final Map<String, List<VersionShim>> shimsBySourceVersion = new HashMap<>();

    // loader -> sourceVersion -> shims
    private final Map<String, Map<String, List<VersionShim>>> shimsByLoaderAndVersion = new HashMap<>();

    private final List<VersionShim> allShims = new ArrayList<>();

    public void register(VersionShim shim) {
        allShims.add(shim);

        shimsBySourceVersion
            .computeIfAbsent(shim.getSourceVersion(), k -> new ArrayList<>())
            .add(shim);

        shimsByLoaderAndVersion
            .computeIfAbsent(shim.getModLoaderType(), k -> new HashMap<>())
            .computeIfAbsent(shim.getSourceVersion(), k -> new ArrayList<>())
            .add(shim);
    }

    public List<VersionShim> getShimsForVersion(String sourceVersion) {
        return shimsBySourceVersion.getOrDefault(sourceVersion, Collections.emptyList());
    }

    public List<VersionShim> getShimsForLoaderAndVersion(String modLoader, String sourceVersion) {
        Map<String, List<VersionShim>> byVersion = shimsByLoaderAndVersion.get(
                canonicalLoader(modLoader));
        if (byVersion == null) return Collections.emptyList();
        return byVersion.getOrDefault(sourceVersion, Collections.emptyList());
    }
    
    /**
     * Resolve an intermediate MC version (1.16.2) to its milestone (1.16.5). Milestones and
     * unknown versions pass through unchanged.
     */
    public static String resolveVersion(String version) {
        return VERSION_ALIASES.getOrDefault(version, version);
    }

    /** Every version the registry handles: milestones with shims plus their intermediate aliases. */
    public Set<String> getAllKnownVersions() {
        Set<String> versions = new HashSet<>();
        versions.addAll(shimsBySourceVersion.keySet());
        versions.addAll(VERSION_ALIASES.keySet());
        return Collections.unmodifiableSet(versions);
    }

    /**
     * Find the shortest shim chain from sourceVersion to targetVersion, resolving intermediate
     * versions to milestones first. Returns an empty list when no path exists.
     */
    public List<VersionShim> findShimChain(String modLoader, String sourceVersion, String targetVersion) {
        // A toml with only loaderVersion (no minecraft dep) leaves the version null; bail before the
        // equals below NPEs.
        if (sourceVersion == null || targetVersion == null) {
            return Collections.emptyList();
        }

        String resolvedSource = resolveVersionForLoader(modLoader, sourceVersion);
        String resolvedTarget = resolveVersionForLoader(modLoader, targetVersion);

        if (!resolvedSource.equals(sourceVersion)) {
            LOGGER.info("Resolved source version " + sourceVersion + " → " + resolvedSource
                    + " (fuzzy version matching)");
        }
        if (!resolvedTarget.equals(targetVersion)) {
            LOGGER.info("Resolved target version " + targetVersion + " → " + resolvedTarget
                    + " (fuzzy version matching)");
        }

        // BFS so the first path found uses the fewest shims.
        Queue<ShimPath> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();

        queue.add(new ShimPath(resolvedSource, new ArrayList<>()));
        visited.add(resolvedSource);

        Map<String, List<VersionShim>> byVersion = shimsByLoaderAndVersion.get(
                canonicalLoader(modLoader));

        while (!queue.isEmpty()) {
            ShimPath current = queue.poll();

            if (current.version.equals(resolvedTarget)) {
                return current.shims;
            }

            List<VersionShim> shimsHere = (byVersion == null)
                    ? Collections.<VersionShim>emptyList()
                    : byVersion.getOrDefault(current.version, Collections.emptyList());
            for (VersionShim shim : shimsHere) {
                // API-version numbers are not Minecraft versions. Keeping API shims out of the
                // graph prevents an accidental numeric collision from bypassing an MC transition.
                if (isAuxiliaryShim(shim)) {
                    continue;
                }
                String nextVersion = shim.getTargetVersion();

                if (visited.add(nextVersion)) {
                    List<VersionShim> newPath = new ArrayList<>(current.shims);
                    newPath.add(shim);

                    queue.add(new ShimPath(nextVersion, newPath));
                }
            }
        }

        return Collections.emptyList();
    }

    /**
     * Forge has one 1.20 milestone shim and no patch-release API shims. Treat its 1.20.x source
     * and host versions as that milestone, while leaving Fabric and NeoForge alone because they
     * have real patch-to-patch transitions in this range.
     */
    private static String resolveVersionForLoader(String modLoader, String version) {
        String resolved = resolveVersion(version);
        if ("forge".equalsIgnoreCase(canonicalLoader(modLoader))
                && resolved.matches("1\\.20\\.[1-6]")) {
            return "1.20";
        }
        return resolved;
    }

    public List<VersionShim> getAllShims() {
        return Collections.unmodifiableList(allShims);
    }

    /**
     * Returns API shims that can apply to the requested loader.
     *
     * <p>API shims sit outside the Minecraft version graph. Offline transforms apply this list
     * alongside the proven version chain, which includes {@code common} providers without also
     * registering unrelated version transitions.
     */
    public List<VersionShim> findApiShimsForLoader(
            String modLoader, String targetMcVersion) {
        return allShims.stream()
                .filter(ShimRegistry::isAuxiliaryShim)
                .filter(shim -> loaderMatches(modLoader, shim.getModLoaderType()))
                .filter(shim -> isAvailableOnHost(shim, targetMcVersion))
                .toList();
    }

    /**
     * Compatibility overload for callers that use the detected runtime target.
     * Offline transforms should pass their requested target explicitly.
     */
    public List<VersionShim> findApiShimsForLoader(String modLoader) {
        return findApiShimsForLoader(modLoader, RetromodVersion.TARGET_MC_VERSION);
    }

    /**
     * Returns the loader-compatible shims whose target is available on the requested host.
     *
     * <p>This is the conservative fallback for an explicitly staged or requested transform whose
     * source Minecraft version is absent. Automatic scans should keep using
     * {@code needsTransformation}, since an unknown version can also belong to a native mod.
     */
    public List<VersionShim> findShimsForUnknownSource(String modLoader, String targetVersion) {
        List<VersionShim> applicable = new ArrayList<>();
        for (VersionShim shim : allShims) {
            if (!loaderMatches(modLoader, shim.getModLoaderType())
                    || !isAvailableOnHost(shim, targetVersion)) {
                continue;
            }
            applicable.add(shim);
        }
        return List.copyOf(applicable);
    }

    /** Returns whether a provider can safely register against the requested Minecraft host. */
    public static boolean isAvailableOnHost(VersionShim shim, String targetMcVersion) {
        if (shim == null || targetMcVersion == null || targetMcVersion.isBlank()) {
            return false;
        }
        if (shim instanceof AuxiliaryVersionShim
                && !(shim instanceof MinecraftVersionedApiShim)) {
            return true;
        }
        return !RetromodVersion.mcVersionExceeds(shim.getTargetVersion(), targetMcVersion);
    }

    private static boolean isAuxiliaryShim(VersionShim shim) {
        return shim instanceof AuxiliaryVersionShim;
    }

    private static boolean loaderMatches(String modLoader, String shimLoader) {
        if (shimLoader == null
                || "any".equalsIgnoreCase(shimLoader)
                || "common".equalsIgnoreCase(shimLoader)) {
            return true;
        }
        if (modLoader == null) {
            return false;
        }
        String canonicalModLoader = canonicalLoader(modLoader);
        String canonicalShimLoader = canonicalLoader(shimLoader);
        return canonicalShimLoader.equalsIgnoreCase(canonicalModLoader)
                || ("neoforge".equalsIgnoreCase(canonicalModLoader)
                        && "forge".equalsIgnoreCase(canonicalShimLoader));
    }

    /** Quilt uses Fabric bytecode, mappings, Mixins, and version transitions. */
    private static String canonicalLoader(String loader) {
        return loader != null && "quilt".equalsIgnoreCase(loader) ? "fabric" : loader;
    }

    /** Source versions with at least one shim for the given loader. */
    public Set<String> getSupportedVersions(String modLoader) {
        Map<String, List<VersionShim>> byVersion = shimsByLoaderAndVersion.get(
                canonicalLoader(modLoader));
        if (byVersion == null) return Collections.emptySet();
        return Collections.unmodifiableSet(byVersion.keySet());
    }
    
    /** BFS node: a version we've reached and the chain of shims that got us there. */
    private record ShimPath(String version, List<VersionShim> shims) {}
}
