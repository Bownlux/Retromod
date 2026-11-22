/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.network;

import com.retromod.legacyapi.minecraft.network.chat.LegacyComponentJson;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

/** {@code FriendlyByteBuf.readComponent} and {@code writeComponent}, which were gone by 1.21 for registry-aware stream codecs. */
public final class LegacyBufComponents {

    // The old buffer wrote a component as its JSON text, with this length limit.
    private static final int MAX_JSON = 262144;

    private LegacyBufComponents() {}

    /** Reads a component written as JSON, as the old buffer did. */
    public static Component readComponent(FriendlyByteBuf buf) {
        return LegacyComponentJson.fromJson(buf.readUtf(MAX_JSON));
    }

    /** Writes a component as JSON, as the old buffer did, so a mod's own reader on the other side reads it back. */
    public static FriendlyByteBuf writeComponent(FriendlyByteBuf buf, Component component) {
        buf.writeUtf(LegacyComponentJson.toJson(component), MAX_JSON);
        return buf;
    }
}
