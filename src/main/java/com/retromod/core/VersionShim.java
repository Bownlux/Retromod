/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

/**
 * A version-specific compatibility shim. Each implementation handles one version
 * transition (1.21.9 to 1.21.10) and is discovered via ServiceLoader, so external
 * shim packs can be added.
 * Library-versioned API providers implement {@link AuxiliaryVersionShim}. API providers whose
 * availability is tied to the Minecraft host implement {@link MinecraftVersionedApiShim}.
 *
 * <p><b>Public Addon API.</b> Stable extension point for third-party addons (see
 * {@code docs/addons.md}). Across the 1.x line new methods are added only as
 * {@code default}s, so existing signatures stay intact.
 */
public interface VersionShim {
    
    /**
     * @return Human-readable name of this shim (e.g., "Fabric 1.21.9 to 1.21.10")
     */
    String getShimName();
    
    /**
     * @return Source MC version this shim handles (e.g., "1.21.9")
     */
    String getSourceVersion();
    
    /**
     * @return Target MC version this shim transforms to (e.g., "1.21.10")
     */
    String getTargetVersion();
    
    /**
     * @return Mod loader type ("fabric", "forge", "neoforge")
     */
    String getModLoaderType();
    
    /**
     * Register all method/field/class redirects for this version transition.
     *
     * @param transformer The transformer to register redirects with
     */
    void registerRedirects(RetromodTransformer transformer);

    /**
     * Classes that provide replacement code for removed methods.
     *
     * @return Array of fully qualified class names
     */
    default String[] getShimClasses() {
        return new String[0];
    }

    /**
     * Whether this shim applies to a mod, based on its metadata.
     *
     * @param modId The mod's ID
     * @param modVersion The mod's version
     * @param targetMcVersion The MC version the mod targets
     * @return true if this shim should be applied
     */
    default boolean appliesTo(String modId, String modVersion, String targetMcVersion) {
        return getSourceVersion().equals(targetMcVersion);
    }
}
