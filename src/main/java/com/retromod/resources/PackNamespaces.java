/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The asset namespaces a pack actually ships.
 *
 * <p>Pack conversion originally looked only at {@code assets/minecraft}. A pack that also skins mods
 * keeps its textures under {@code assets/<modid>}, and those followed the same 1.13 layout change,
 * so the vanilla half of such a pack converted and the modded half silently did not.
 */
final class PackNamespaces {

    private PackNamespaces() {}

    /**
     * Every {@code assets/<namespace>} directory, vanilla first so its log lines stay familiar.
     *
     * <p>Only a direct child directory counts. A namespace is a plain identifier, so anything with a
     * separator or a traversal segment in its name is skipped rather than followed.
     */
    static List<Path> list(Path packDir) throws IOException {
        Path assets = packDir.resolve("assets");
        if (!Files.isDirectory(assets, LinkOption.NOFOLLOW_LINKS)) return List.of();

        List<Path> namespaces = new ArrayList<>();
        Path vanilla = assets.resolve("minecraft");
        if (Files.isDirectory(vanilla, LinkOption.NOFOLLOW_LINKS)) namespaces.add(vanilla);

        try (var stream = Files.list(assets)) {
            for (Path candidate : stream.sorted().toList()) {
                if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = candidate.getFileName().toString();
                if (name.equals("minecraft") || name.equals(".") || name.equals("..")) continue;
                if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) continue;
                namespaces.add(candidate);
            }
        }
        return List.copyOf(namespaces);
    }
}
