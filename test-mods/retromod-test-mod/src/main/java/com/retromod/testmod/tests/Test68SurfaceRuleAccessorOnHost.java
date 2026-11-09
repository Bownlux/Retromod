/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import com.retromod.testmod.mixin.LegacySurfaceRuleSettingsAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.surfacebuilder.MaterialRules;

/** The settings accessor reads the overworld's surface rule and stores a replacement, which 26.3 keeps in a holder. */
public final class Test68SurfaceRuleAccessorOnHost implements Test {

    private final MinecraftServer server;

    public Test68SurfaceRuleAccessorOnHost(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String description() {
        return "the settings accessor reads the surface rule and stores a replacement";
    }

    @Override
    public TestResult run() {
        // A flat overworld has no noise settings, so this reads the Nether's then.
        var world = Test66SurfaceRulesOnHost.noiseWorld(server);
        if (world == null || !(world.getChunkManager().getChunkGenerator() instanceof NoiseChunkGenerator generator)) {
            return TestResult.fail("neither the overworld nor the Nether generates from noise settings");
        }
        LegacySurfaceRuleSettingsAccessor access = (LegacySurfaceRuleSettingsAccessor) (Object) generator.getSettings().value();
        MaterialRules.MaterialRule original = access.retromodTest$getSurfaceRule();
        if (original == null) return TestResult.fail("the accessor read no rule");
        MaterialRules.MaterialRule replacement = MaterialRules.sequence(original);
        access.retromodTest$setSurfaceRule(replacement);
        boolean stored = access.retromodTest$getSurfaceRule() == replacement;
        // The world keeps generating with its own rule.
        access.retromodTest$setSurfaceRule(original);
        return stored ? TestResult.success() : TestResult.fail("the accessor did not store the replacement");
    }
}
