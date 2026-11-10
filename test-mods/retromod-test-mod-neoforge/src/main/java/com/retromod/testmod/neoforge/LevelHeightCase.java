/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;

/** The level height getters 1.21.2 renamed, called and overridden the way a 1.21.1 mod does. */
final class LevelHeightCase {

    private LevelHeightCase() {}

    /** Reads the overworld's heights and a mod's own height accessor. */
    static String run(ServerLevel level) {
        // 1.21.2 renamed getMinBuildHeight to getMinY. The overworld still starts at -64.
        if (level.getMinBuildHeight() != -64) return "fail: the floor read as " + level.getMinBuildHeight();
        // getMaxBuildHeight was one past the top, which 1.21.2's getMaxY is not.
        if (level.getMaxBuildHeight() != 320) return "fail: the ceiling read as " + level.getMaxBuildHeight();
        if (level.getMinSection() != -4 || level.getMaxSection() != 20) {
            return "fail: the sections read as " + level.getMinSection() + " to " + level.getMaxSection();
        }
        // The host's own height checks call a mod's override by the renamed name.
        LevelHeightAccessor flat = new LevelHeightAccessor() {
            @Override public int getHeight() { return 16; }
            @Override public int getMinBuildHeight() { return 100; }
        };
        if (flat.isOutsideBuildHeight(100) || !flat.isOutsideBuildHeight(99) || !flat.isOutsideBuildHeight(116)) {
            return "fail: the host did not read the mod's floor of 100";
        }
        return "success";
    }
}
