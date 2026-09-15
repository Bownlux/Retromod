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

    PackFormat {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("Pack format components must be nonnegative");
        }
    }

    static PackFormat resourceTarget(String minecraftVersion) {
        return target(minecraftVersion).resource();
    }

    static PackFormat dataTarget(String minecraftVersion) {
        return target(minecraftVersion).data();
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
