/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import java.util.Locale;

/**
 * What one transform is translating for, which providers read to decide whether they apply.
 *
 * @param loader           the mod's loader family: {@code fabric} (Fabric and Quilt), {@code forge},
 *                         {@code neoforge}, or {@code unknown}
 * @param hostVersion      the Minecraft version the output runs on
 * @param path             the transform path that asked, for logs and tests only
 * @param modSourceVersion the Minecraft version the mod was built for, or null when unknown
 */
public record LoaderApiContext(String loader, String hostVersion, TransformPath path,
        String modSourceVersion) {

    /** Every way a jar or class reaches the transformer. */
    public enum TransformPath {
        FABRIC_PRELAUNCH, FABRIC_INIT, FABRIC_JAR, CLI_TRANSFORM, CLI_BATCH, AOT, FULL_AOT,
        API_EMBEDDER, CLASS_LOAD, TEST
    }

    public LoaderApiContext {
        loader = loaderFamily(loader);
    }

    /** Maps a mod loader type to the family a provider targets. Quilt runs Fabric mods. */
    public static String loaderFamily(String modLoaderType) {
        if (modLoaderType == null) return "unknown";
        String loader = modLoaderType.trim().toLowerCase(Locale.ROOT);
        return switch (loader) {
            case "fabric", "quilt" -> "fabric";
            case "forge", "neoforge" -> loader;
            default -> "unknown";
        };
    }

    /** The part of the context that changes what a provider registers. */
    String applicabilityKey() {
        return loader + "@" + hostVersion;
    }

    /** The same context for another path. */
    public LoaderApiContext withPath(TransformPath newPath) {
        return new LoaderApiContext(loader, hostVersion, newPath, modSourceVersion);
    }
}
