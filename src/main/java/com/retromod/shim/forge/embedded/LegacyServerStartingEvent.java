/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stands in for 1.12.2's {@code FMLServerStartingEvent}, which a mod received to register its
 * commands. {@link LegacyForgeEvents} fires it from the host's own server-starting event.
 */
public final class LegacyServerStartingEvent {
    private final Object server;

    public LegacyServerStartingEvent(Object server) {
        this.server = server;
    }

    public Object getServer() {
        return server;
    }

    /** {@code registerServerCommand(command)}: registered with the host's command dispatcher. */
    public void registerServerCommand(Object command) {
        LegacyCommands.add(command);
    }
}
