/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.util;

import com.mojang.blaze3d.Blaze3D;
import net.minecraft.util.Util;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;

/** {@code Util.OS.openUri} and its siblings, which 26.3 moved to {@code Blaze3D}. */
public final class LegacyOsOpeners {

    private LegacyOsOpeners() {}

    /** {@code os.openUri(uri)}. */
    public static void openUri(Util.OS os, URI uri) {
        Blaze3D.openUri(uri);
    }

    /** {@code os.openUri(text)}: text that is not a URI opens nothing, as a failed open did before. */
    public static void openUri(Util.OS os, String uri) {
        try {
            Blaze3D.openUri(new URI(uri));
        } catch (URISyntaxException notUri) {
            // The old call logged and returned on a bad link too.
        }
    }

    /** {@code os.openFile(file)}. */
    public static void openFile(Util.OS os, File file) {
        Blaze3D.openPath(file.toPath());
    }

    /** {@code os.openPath(path)}. */
    public static void openPath(Util.OS os, Path path) {
        Blaze3D.openPath(path);
    }
}
