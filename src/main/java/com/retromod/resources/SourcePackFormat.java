/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.core.RetromodVersion;

import java.util.LinkedHashMap;
import java.util.Map;

/** Looks up the resource pack format of the Minecraft version a mod was built for. */
final class SourcePackFormat {

    // The first release of each resource format before 1.20, oldest first. 1.13, 1.16.1, 1.20.1 and
    // 1.21.1 were checked against their client jars' pack.mcmeta or version.json. The others are the
    // published format history that ResourcePackTransformer also documents.
    private static final Map<String, Integer> LEGACY_RESOURCE_FORMATS = ordered(
            "1.6", 1, "1.9", 2, "1.11", 3, "1.13", 4, "1.15", 5, "1.16.2", 6, "1.17", 7,
            "1.18", 8, "1.19", 9, "1.19.3", 12, "1.19.4", 13);

    private SourcePackFormat() {
    }

    /** The resource format a {@code minecraftVersion} mod shipped, or null when unknown or too old. */
    static PackFormat resource(String minecraftVersion) {
        // The versioned texture moves are keyed on pack formats. A jar names a Minecraft version
        // instead, and many 1.12 jars declare a wrong pack_format in their own pack.mcmeta, so the
        // format comes from the version rather than the jar.
        if (minecraftVersion == null || !minecraftVersion.matches("\\d+\\.\\d+.*")) return null;
        // PackFormat only knows the hosts Retromod runs on, 1.20 and newer.
        if (RetromodVersion.compareMcVersions(minecraftVersion, "1.20") >= 0) {
            return PackFormat.resourceTargetOrNull(minecraftVersion);
        }
        // An older version takes the format of the newest first release at or before it.
        Integer format = null;
        for (Map.Entry<String, Integer> first : LEGACY_RESOURCE_FORMATS.entrySet()) {
            if (RetromodVersion.compareMcVersions(minecraftVersion, first.getKey()) < 0) break;
            format = first.getValue();
        }
        return format == null ? null : new PackFormat(format, 0);
    }

    private static Map<String, Integer> ordered(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (Integer) pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(map);
    }
}
