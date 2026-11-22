/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.server.packs;

import com.retromod.shim.common.embedded.LegacyLog;
import java.util.List;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;

/** {@code pack.open()}, which 26.3 returns as a stream of resource layers instead of one composite. */
public final class LegacyPackOpening {

    private LegacyPackOpening() {}

    /** The pack's resources; a pack with several layers answers its first, with one warning. */
    public static PackResources open(Pack pack) {
        List<PackResources> layers = pack.open().toList();
        if (layers.isEmpty()) throw new IllegalStateException("Retromod: the pack opened with no resources");
        if (layers.size() > 1) {
            // 26.3 has no composite to merge layers into, so the rest are closed rather than leaked.
            for (PackResources extra : layers.subList(1, layers.size())) close(extra);
            LegacyLog.warnOnce("legacy pack open " + LegacyPackOpening.class.getName(), "An old mod opened a resource pack"
                    + " that has several layers. It reads only the first one, so overlay files in that pack are missing for it.");
        }
        return layers.get(0);
    }

    // Closed through AutoCloseable, which every host's pack resources implement.
    private static void close(PackResources layer) {
        try {
            ((AutoCloseable) layer).close();
        } catch (Exception ignored) {
            // A layer that fails to close holds only its file handles until the game exits.
        }
    }
}
