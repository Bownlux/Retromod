/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.mixin;

import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.surfacebuilder.MaterialRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Regions Unexplored's shape: read and replace the settings' surface rule through an accessor; 26.3 keeps it in a holder. */
@Mixin(ChunkGeneratorSettings.class)
public interface LegacySurfaceRuleSettingsAccessor {

    @Accessor("surfaceRule")
    @Mutable
    void retromodTest$setSurfaceRule(MaterialRules.MaterialRule rule);

    @Accessor("surfaceRule")
    MaterialRules.MaterialRule retromodTest$getSurfaceRule();
}
