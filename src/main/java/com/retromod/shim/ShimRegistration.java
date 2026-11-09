/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers one version shim so a failure skips only that shim. */
public final class ShimRegistration {

    // The logger the entry points use, so the warning lands beside their summary lines.
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private ShimRegistration() {}

    /** Registers the shim's redirects, or warns and returns false when it fails. */
    public static boolean register(VersionShim shim, RetromodTransformer transformer) {
        try {
            shim.registerRedirects(transformer);
            return true;
        } catch (RuntimeException | LinkageError e) {
            // A missing class or a bad host probe in one shim must not drop the shims after it.
            LOGGER.warn("Could not register the version shim {} ({} to {}). Mods that need it may "
                            + "fail; the other shims still apply. Please report this with the log.",
                    shim.getClass().getName(), shim.getSourceVersion(), shim.getTargetVersion(), e);
            return false;
        }
    }
}
