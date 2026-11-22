/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.entity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;

/** The hitbox switch the entity render dispatcher held before it became a debug screen entry. */
public final class LegacyHitBoxes {

    private LegacyHitBoxes() {}

    /** {@code dispatcher.shouldRenderHitBoxes()}: whether the hitbox debug entry is on. */
    public static boolean shouldRenderHitBoxes(EntityRenderDispatcher dispatcher) {
        return Minecraft.getInstance().debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES);
    }

    /** {@code dispatcher.setRenderHitBoxes(show)}: turns the hitbox debug entry on or off. */
    public static void setRenderHitBoxes(EntityRenderDispatcher dispatcher, boolean show) {
        DebugScreenEntryList entries = Minecraft.getInstance().debugEntries;
        // The entry list only toggles, so it is toggled only when it differs from the wanted state.
        if (entries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES) != show) {
            entries.toggleStatus(DebugScreenEntries.ENTITY_HITBOXES);
        }
    }
}
