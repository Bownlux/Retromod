/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The level height getters 1.21.2 renamed, called and overridden the way a 1.20.1 mod does. */
final class LevelHeightCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");

    private LevelHeightCase() {}

    /** Checks once a server runs, so the overworld's height is known. */
    static void register() {
        MinecraftForge.EVENT_BUS.addListener(LevelHeightCase::check);
    }

    private static void check(ServerStartedEvent event) {
        String result;
        try {
            result = run(event.getServer().overworld());
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-Forge] Level height getters renamed in 1.21.2, called and overridden (server started): {}",
                result);
    }

    private static String run(ServerLevel level) {
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
