/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.network.chat;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.TextColor;

/** Chat calls that returned a plain value in 1.20.1, read from the result or holder that replaced it. */
public final class LegacyChatCalls {

    private LegacyChatCalls() {}

    /** {@code TextColor.parseColor(text)}: the color the text names, or null for text that names none, as it was before 1.20.5. */
    public static TextColor parseColor(String text) {
        return TextColor.parseColor(text).result().orElse(null);
    }

    /** {@code bound.chatType()}: the chat type itself, which a holder wraps from 1.20.5. */
    public static ChatType chatType(ChatType.Bound bound) {
        return bound.chatType().value();
    }
}
