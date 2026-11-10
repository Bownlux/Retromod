/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.gui.overlay;

import com.google.common.collect.ImmutableList;
import com.retromod.legacyapi.forge.client.LegacyOverlayHub;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;

/** Forge 1.20.1's overlay lookup, over the one overlay order every translated mod registers into. */
public final class GuiOverlayManager {

    private GuiOverlayManager() {}

    /** Every vanilla and registered overlay in the old drawing order, as this mod's {@link NamedGuiOverlay}s. */
    public static ImmutableList<NamedGuiOverlay> getOverlays() {
        List<NamedGuiOverlay> overlays = new ArrayList<>();
        for (Identifier id : LegacyOverlayHub.orderedIds()) overlays.add(NamedGuiOverlay.of(id));
        return ImmutableList.copyOf(overlays);
    }

    /** This mod's {@link NamedGuiOverlay} for a vanilla or registered id, the one render events report; null for any other. */
    public static NamedGuiOverlay findOverlay(Identifier id) {
        return LegacyOverlayHub.isKnown(id) ? NamedGuiOverlay.of(id) : null;
    }
}
