/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.event;

import net.neoforged.neoforge.event.TagsUpdatedEvent;

/** NeoForge 1.21.1's {@code TagsUpdatedEvent.UpdateCause}, which 26.3 replaced with two event classes. */
public enum TagsUpdateCause {
    SERVER_DATA_LOAD,
    CLIENT_PACKET_RECEIVED;

    /** {@code TagsUpdatedEvent.getUpdateCause()}: 26.3 fires a subclass for each cause instead. */
    public static TagsUpdateCause of(TagsUpdatedEvent event) {
        return event instanceof TagsUpdatedEvent.ClientPacketReceived ? CLIENT_PACKET_RECEIVED : SERVER_DATA_LOAD;
    }
}
