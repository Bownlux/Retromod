/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.gui.overlay;

import com.retromod.legacyapi.forge.client.LegacyOverlayHub;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.gui.overlay.ForgeLayer;

/** Forge 1.20.1's named HUD overlay; each id has one instance in a mod, so old {@code ==} comparisons still hold. */
public final class NamedGuiOverlay {

    // One instance per id in this mod's copy: getOverlay() == VanillaGuiOverlay.CROSSHAIR.type() compares these.
    private static final Map<Identifier, NamedGuiOverlay> CANONICAL = new ConcurrentHashMap<>();

    private final Identifier id;
    private final IGuiOverlay overlay;
    private volatile IGuiOverlay through;

    /** The old record's constructor; an overlay built this way is a separate object from the one events report. */
    public NamedGuiOverlay(Identifier id, IGuiOverlay overlay) {
        this.id = id;
        this.overlay = overlay;
    }

    /** This mod's one instance for {@code id}. */
    public static NamedGuiOverlay of(Identifier id) {
        return CANONICAL.computeIfAbsent(id, key -> new NamedGuiOverlay(key, null));
    }

    public Identifier id() {
        return id;
    }

    /** What draws the overlay: the one given, this mod's own, or one drawing through the shared HUD; null for an unknown id. */
    public IGuiOverlay overlay() {
        if (overlay != null) return overlay;
        IGuiOverlay own = LegacyOverlayHub.ownOverlay(id);
        if (own != null) return own;
        if (!LegacyOverlayHub.isKnown(id)) return null;
        IGuiOverlay shared = through;
        if (shared == null) {
            // Looked up when it draws, since the HUD coordinator fills in the vanilla layers after mods construct.
            shared = (gui, graphics, partialTick, width, height) -> drawThrough(id, graphics);
            through = shared;
        }
        return shared;
    }

    private static void drawThrough(Identifier id, GuiGraphicsExtractor graphics) {
        ForgeLayer layer = LegacyOverlayHub.sharedLayer(id);
        if (layer != null) layer.extract(graphics, Minecraft.getInstance().getDeltaTracker());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NamedGuiOverlay named && named.id.equals(id) && Objects.equals(named.overlay, overlay);
    }

    @Override
    public int hashCode() {
        return 31 * id.hashCode() + Objects.hashCode(overlay);
    }

    @Override
    public String toString() {
        return "NamedGuiOverlay[id=" + id + ", overlay=" + overlay + "]";
    }
}
