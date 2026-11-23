/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.gui.overlay;

import net.minecraft.resources.Identifier;

/** Forge 1.20.1's overlay lookup, answered from the old vanilla ids and this mod's own overlays. */
public final class GuiOverlayManager {

    private GuiOverlayManager() {}

    /** The overlay for {@code id}: a vanilla one or one this mod registered; null for any other. */
    public static NamedGuiOverlay findOverlay(Identifier id) {
        if (id == null) return null;
        return LegacyGuiLayers.isVanilla(id) || LegacyGuiLayers.ownOverlay(id) != null ? NamedGuiOverlay.of(id) : null;
    }
}
