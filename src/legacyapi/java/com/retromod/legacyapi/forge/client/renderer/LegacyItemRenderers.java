/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.renderer;

import com.retromod.legacyapi.forge.client.LegacyClientRenderBootstrap;
import com.retromod.shim.common.embedded.LegacyLog;

/** Sets up Forge 1.20.1's item renderers for each old mod that has them, on a client only; safe to call on a server. */
public final class LegacyItemRenderers {

    /** The path of the item model type each mod's namespace gets; Retromod's item definitions name it. */
    public static final String MODEL_TYPE = "retromod_legacy_item";

    private LegacyItemRenderers() {}

    /** Called first in each old {@code @Mod} constructor of a jar that names BlockEntityWithoutLevelRenderer. */
    public static void install() {
        LegacyClientRenderBootstrap.install();
        if (!LegacyClientRenderBootstrap.isClient()) return;
        try {
            // Client classes are reached only from here, so a dedicated server never loads them.
            LegacyItemRendererTypes.addTypes();
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warn("Retromod did not set up the old item renderers of " + LegacyClientRenderBootstrap.namespaces() + ": "
                    + failure);
        }
    }
}
