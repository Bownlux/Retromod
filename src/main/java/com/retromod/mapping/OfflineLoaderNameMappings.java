/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

/** Configures the member namespace for one offline mod transform. */
public final class OfflineLoaderNameMappings {

    private OfflineLoaderNameMappings() {}

    /** Counts used by CLI diagnostics. */
    public record Result(int intermediaryMappings, int srgMappings) {}

    /**
     * Rebuilds loader-specific member mappings for one mod.
     *
     * <p>Offline batch transforms reuse one transformer. Fabric intermediary names and Forge SRG
     * names must therefore be cleared before selecting the namespace for the next jar.
     */
    public static Result configure(RetromodTransformer transformer, String loader,
            String targetMcVersion, boolean neoForgeTarget) {
        transformer.clearIntermediaryNameMappings();
        transformer.clearSrgNameMappings();

        if ("fabric".equalsIgnoreCase(loader)) {
            if (!RetromodVersion.isUnobfuscatedTarget(targetMcVersion)) {
                return new Result(0, 0);
            }
            try {
                int mappings = IntermediaryToMojangMapper.applyTo(transformer);
                return new Result(mappings, 0);
            } catch (Exception e) {
                return new Result(0, 0);
            }
        }

        if (!"forge".equalsIgnoreCase(loader) && !"neoforge".equalsIgnoreCase(loader)) {
            return new Result(0, 0);
        }

        try {
            // NeoForge uses Mojang member names. A Forge source mod aimed at NeoForge still needs
            // its source SRG names decoded, but must not receive a Forge target-SRG namespace.
            boolean mojangTarget = RetromodVersion.usesOfficialForgeRuntimeNames(targetMcVersion)
                    || neoForgeTarget || "neoforge".equalsIgnoreCase(loader);
            if (mojangTarget) {
                int source = SrgToMojangMapper.getInstance().applyTo(transformer);
                return new Result(0, source);
            }

            TargetSrgMapper target = TargetSrgMapper.forVersion(targetMcVersion);
            int targetCount = target.applyTo(transformer);
            if (targetCount == 0) {
                return new Result(0, 0);
            }
            int source = SrgToMojangMapper.getInstance().applyTo(transformer);
            return new Result(0, source + targetCount);
        } catch (Exception e) {
            transformer.clearSrgNameMappings();
            return new Result(0, 0);
        }
    }
}
