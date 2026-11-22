/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.biome;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs an old mod's per-position biome handler before 26.3's own resolver, as the removed
 * {@code getNoiseBiome(x, y, z, sampler)} ran it at its head. The host's answer is used when the handler sets none.
 */
public final class LegacyNoiseBiomeResolver implements BiomeResolver {

    private final LegacyNoiseBiomeHook hook;
    private final Climate.Sampler sampler;
    private final BiomeResolver host;

    public LegacyNoiseBiomeResolver(LegacyNoiseBiomeHook hook, Climate.Sampler sampler, BiomeResolver host) {
        this.hook = hook;
        this.sampler = sampler;
        this.host = host;
    }

    @Override
    public Holder<?> getNoiseBiome(int x, int y, int z) {
        CallbackInfoReturnable<Object> callback = new CallbackInfoReturnable<>("getNoiseBiome", true);
        hook.retromod$legacyNoiseBiome(x, y, z, sampler, callback);
        return callback.isCancelled() ? (Holder<?>) callback.getReturnValue() : host.getNoiseBiome(x, y, z);
    }
}
