/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stands in for 1.12.2's {@code ClientCommandHandler}. A command registered here runs on the
 * client only, as before; {@link LegacyForgeEvents} adds it to the host's client command
 * dispatcher.
 */
public final class LegacyClientCommandHandler {
    public static final LegacyClientCommandHandler instance = new LegacyClientCommandHandler();
    public static final LegacyClientCommandHandler field_71462_a = instance;

    private LegacyClientCommandHandler() {}

    public Object registerCommand(Object command) {
        LegacyCommands.addClient(command);
        return command;
    }
    public Object func_71560_a(Object command) { return registerCommand(command); }
}
