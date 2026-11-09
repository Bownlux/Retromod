/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Recognizes jars Retromod already produced, so in-place mode never translates its own output
 * a second time.
 *
 * <p>{@code ForgeModTransformer}, {@code FabricModTransformer}, and the CLI's Forge and NeoForge
 * output stamp {@code Retromod-Transformed}. {@code AotCompiler}, which the Forge and NeoForge
 * in-game picker used before 1.4.0-snapshot.4 to install {@code mods/<name>-retromod.jar}, stamps
 * only its own {@code Retromod-AOT-*} keys. Such an output can still sit in {@code mods/}, and its
 * widened range still starts at the mod's old Minecraft version, so a check on
 * {@code Retromod-Transformed} alone would read it as an old mod and translate it again. Both
 * markers count. Output stamping itself is left unchanged so rewrite mode keeps producing the
 * same bytes as before.
 */
public final class TranslatedJarMarkers {

    /** Written by the runtime transformers. */
    public static final String TRANSFORMED = "Retromod-Transformed";

    /** Written by {@code AotCompiler} on every compiled jar. */
    public static final String AOT_VERSION = "Retromod-AOT-Version";

    /** The Minecraft version a jar was translated for, written by both. */
    public static final String TARGET_VERSION = "Retromod-Target-Version";

    /** Written on a jar a dedicated server translated, which left out the mod's client-only code. */
    public static final String DIST = "Retromod-Dist";

    /** The {@link #DIST} value of such a jar. */
    public static final String DEDICATED_SERVER = "DEDICATED_SERVER";

    private TranslatedJarMarkers() {}

    /** True when the jar was translated on a dedicated server and lacks its client-only code. */
    public static boolean isServerOnly(Manifest manifest) {
        if (manifest == null) return false;
        String dist = manifest.getMainAttributes().getValue(DIST);
        return dist != null && DEDICATED_SERVER.equals(dist.trim());
    }

    /** True when the manifest belongs to a jar Retromod already translated. */
    public static boolean isRetromodOutput(Manifest manifest) {
        if (manifest == null) return false;
        Attributes main = manifest.getMainAttributes();
        String transformed = main.getValue(TRANSFORMED);
        if (transformed != null && transformed.trim().equalsIgnoreCase("true")) return true;
        String aotVersion = main.getValue(AOT_VERSION);
        return aotVersion != null && !aotVersion.isBlank();
    }
}
