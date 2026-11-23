/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.gui.overlay;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/** Forge 1.20.1's named overlay record, with one instance per id in this mod, as render events report them. */
public final class NamedGuiOverlay {

    // One instance per id in this mod's copy: getOverlay() == VanillaGuiOverlay.CROSSHAIR.type() compares these.
    private static final Map<Identifier, NamedGuiOverlay> CANONICAL = new ConcurrentHashMap<>();

    private final Identifier id;
    private final IGuiOverlay overlay;

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

    /** What draws the overlay: the one given or this mod's own; null for a vanilla overlay, which NeoForge draws itself. */
    public IGuiOverlay overlay() {
        return overlay != null ? overlay : LegacyGuiLayers.ownOverlay(id);
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
