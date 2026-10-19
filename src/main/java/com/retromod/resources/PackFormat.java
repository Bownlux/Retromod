/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import java.util.Map;

/** Exact resource-pack and data-pack formats for every published host artifact. */
record PackFormat(int major, int minor) implements Comparable<PackFormat> {

    private static final Map<String, TargetFormats> TARGETS = Map.ofEntries(
        entry("1.20", 15, 0, 15, 0),
        entry("1.20.1", 15, 0, 15, 0),
        entry("1.20.2", 18, 0, 18, 0),
        entry("1.20.3", 22, 0, 26, 0),
        entry("1.20.4", 22, 0, 26, 0),
        entry("1.20.5", 32, 0, 41, 0),
        entry("1.20.6", 32, 0, 41, 0),
        entry("1.21", 34, 0, 48, 0),
        entry("1.21.1", 34, 0, 48, 0),
        entry("1.21.2", 42, 0, 57, 0),
        entry("1.21.3", 42, 0, 57, 0),
        entry("1.21.4", 46, 0, 61, 0),
        entry("1.21.5", 55, 0, 71, 0),
        entry("1.21.6", 63, 0, 80, 0),
        entry("1.21.7", 64, 0, 81, 0),
        entry("1.21.8", 64, 0, 81, 0),
        entry("1.21.9", 69, 0, 88, 0),
        entry("1.21.10", 69, 0, 88, 0),
        entry("1.21.11", 75, 0, 94, 1),
        entry("26.1", 84, 0, 101, 1),
        entry("26.1.1", 84, 0, 101, 1),
        entry("26.1.2", 84, 0, 101, 1),
        entry("26.2", 88, 0, 107, 1),
        // Read from the released 26.3 client's own version.json. The data format moved from 120 to
        // 121 between pre-2 and release, which is why a pre-release value is never left in place.
        entry("26.3", 97, 1, 121, 0)
    );

    // Data formats before 1.20, which TARGETS leaves out because Retromod does not run there. They
    // only tell how old a data pack is, never pick a host. Data packs began at 1.13 with format 4.
    private static final Map<String, Integer> LEGACY_DATA_FORMATS = Map.of(
        "1.13", 4, "1.15", 5, "1.16.2", 6, "1.17", 7, "1.18", 8, "1.18.2", 9, "1.19", 10,
        "1.19.4", 12);

    // The last release that read each resource pack format before 1.13. Packs of these formats
    // still used .lang files and case-insensitive identifiers.
    private static final Map<Integer, String> PRE_1_13_RESOURCE_FORMATS = Map.of(
        1, "1.8.9", 2, "1.10.2", 3, "1.12.2");

    PackFormat {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("Pack format components must be nonnegative");
        }
    }

    /** The newest release whose data packs declare {@code format} or an older one, or null below 4. */
    static String minecraftVersionForData(PackFormat format) {
        // A data pack built for an older game is migrated from that game's layout. No data pack
        // used a format below 4, so nothing matches there.
        String newest = null;
        for (Map.Entry<String, TargetFormats> target : TARGETS.entrySet()) {
            newest = newerIfWithin(newest, target.getKey(), target.getValue().data(), format);
        }
        for (Map.Entry<String, Integer> legacy : LEGACY_DATA_FORMATS.entrySet()) {
            newest = newerIfWithin(newest, legacy.getKey(), new PackFormat(legacy.getValue(), 0),
                format);
        }
        return newest;
    }

    /** The newest release that reads a pre-1.13 resource pack of {@code format}, or null from format 4. */
    static String minecraftVersionForPre113Resources(PackFormat format) {
        return PRE_1_13_RESOURCE_FORMATS.get(format.major());
    }

    private static String newerIfWithin(String newest, String version, PackFormat versionFormat,
                                        PackFormat declared) {
        if (versionFormat.compareTo(declared) > 0) return newest;
        if (newest == null
                || com.retromod.core.RetromodVersion.compareMcVersions(version, newest) > 0) {
            return version;
        }
        return newest;
    }

    static PackFormat resourceTarget(String minecraftVersion) {
        return target(minecraftVersion).resource();
    }

    static PackFormat dataTarget(String minecraftVersion) {
        return target(minecraftVersion).data();
    }

    /** The host's resource format, or null for a host the format table does not cover. */
    static PackFormat resourceTargetOrNull(String minecraftVersion) {
        try {
            return resourceTarget(minecraftVersion);
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    /**
     * Pack formats for a host version, including a pre-release of one.
     *
     * <p>A pre-release host reports its own id, so a 26.3 release candidate arrives as
     * {@code 26.3-rc.1} and never matched the {@code 26.3} key. That threw while the resource
     * manager was being built, which on a Fabric client means every pack transform is skipped for
     * the whole session on exactly the builds people run in the weeks before a release. The shim
     * registry already knows every spelling a milestone answers to, so ask it rather than keeping
     * a second list here.
     */
    private static TargetFormats target(String minecraftVersion) {
        TargetFormats formats = TARGETS.get(minecraftVersion);
        if (formats == null && minecraftVersion != null) {
            formats = TARGETS.get(com.retromod.shim.ShimRegistry.resolveVersion(minecraftVersion));
        }
        if (formats == null) {
            throw new IllegalArgumentException("Unsupported Minecraft pack target: "
                + minecraftVersion + ". Use a published Retromod host version.");
        }
        return formats;
    }

    private static Map.Entry<String, TargetFormats> entry(String minecraftVersion,
                                                            int resourceMajor,
                                                            int resourceMinor,
                                                            int dataMajor,
                                                            int dataMinor) {
        return Map.entry(minecraftVersion,
            new TargetFormats(new PackFormat(resourceMajor, resourceMinor),
                new PackFormat(dataMajor, dataMinor)));
    }

    @Override
    public int compareTo(PackFormat other) {
        int majorComparison = Integer.compare(major, other.major);
        return majorComparison != 0 ? majorComparison : Integer.compare(minor, other.minor);
    }

    String display() {
        return minor == 0 ? Integer.toString(major) : major + "." + minor;
    }

    private record TargetFormats(PackFormat resource, PackFormat data) {}
}
