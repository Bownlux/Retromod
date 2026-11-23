/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.network.chat;

import net.minecraft.network.chat.HoverEvent;

/** {@code HoverEvent.getValue(action)}, read from the record each hover action became in 1.21.5. */
public final class LegacyHoverValues {

    private LegacyHoverValues() {}

    /** The text, the item as the old item info, or the entity info; null when the event is another action, as before. */
    public static Object getValue(HoverEvent event, HoverEvent.Action action) {
        if (event == null || event.action() != action) return null;
        if (event instanceof HoverEvent.ShowText text) return text.value();
        if (event instanceof HoverEvent.ShowItem item) return new LegacyItemStackInfo(item.item().create());
        if (event instanceof HoverEvent.ShowEntity entity) return entity.entity();
        return null;
    }
}
