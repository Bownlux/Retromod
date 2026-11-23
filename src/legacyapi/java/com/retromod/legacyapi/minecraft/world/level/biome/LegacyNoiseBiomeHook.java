/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.biome;

import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An old mod's handler for the per-position biome lookup 26.3 removed, which its repaired mixin adds to the biome source. */
public interface LegacyNoiseBiomeHook {

    /** The handler as the mod wrote it: it may set the biome, which cancels the lookup. */
    void retromod$legacyNoiseBiome(int x, int y, int z, Climate.Sampler sampler, CallbackInfoReturnable<Object> callback);
}
