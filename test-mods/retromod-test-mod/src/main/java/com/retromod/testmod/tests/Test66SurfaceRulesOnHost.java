/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;

/** The host generates a chunk with an old rule source of the mod's own, which 26.3 compiles instead of applying. */
public final class Test66SurfaceRulesOnHost implements Test {

    private final MinecraftServer server;

    public Test66SurfaceRulesOnHost(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String description() {
        return "a new chunk's surface comes from the old rule source the settings mixin returns";
    }

    /**
     * The overworld, or the Nether when the overworld does not use noise settings. A flat overworld, as a
     * regression server uses, never reads a surface rule; its Nether still does.
     */
    static ServerWorld noiseWorld(MinecraftServer server) {
        for (ServerWorld world : new ServerWorld[]{server.getOverworld(), server.getWorld(World.NETHER)}) {
            if (world != null && world.getChunkManager().getChunkGenerator() instanceof NoiseChunkGenerator) return world;
        }
        return null;
    }

    @Override
    public TestResult run() {
        ServerWorld world = noiseWorld(server);
        if (world == null) return TestResult.fail("neither the overworld nor the Nether generates from noise settings");
        int applied = Test65OldSurfaceRules.APPLIED.get();
        int tried = Test65OldSurfaceRules.TRIED.get();
        // A chunk far from anything a reused world generated, so its surface is built now.
        int chunk = 20000 + (int) (System.nanoTime() % 4000);
        var generated = world.getChunk(chunk, chunk);
        if (Test65OldSurfaceRules.APPLIED.get() <= applied || Test65OldSurfaceRules.TRIED.get() <= tried) {
            return TestResult.fail("the generator of " + world.getRegistryKey().getValue() + " never applied the old rule source");
        }
        if (generated.getBlockState(new BlockPos(chunk * 16, world.getBottomY(), chunk * 16)).isAir()) {
            return TestResult.fail("the chunk's floor came out empty");
        }
        return TestResult.success();
    }
}
