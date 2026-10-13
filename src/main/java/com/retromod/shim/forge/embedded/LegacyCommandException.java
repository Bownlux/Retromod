/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stands in for 1.12.2's {@code CommandException} and its subclasses. The message is the old
 * translation key or text; {@link LegacyCommands} shows it to the player when a command fails.
 * Reference parameters are {@code Object} because calls on stand-ins are erased to that shape.
 */
public class LegacyCommandException extends Exception {
    private final transient Object[] args;

    public LegacyCommandException(Object message, Object args) {
        super(String.valueOf(message));
        this.args = args instanceof Object[] array ? array : new Object[0];
    }

    public Object[] getErrorObjects() {
        return args;
    }

    /** {@code WrongUsageException}: the usage text. */
    public static class WrongUsage extends LegacyCommandException {
        public WrongUsage(Object message, Object args) { super(message, args); }
    }

    /** {@code NumberInvalidException}. */
    public static class NumberInvalid extends LegacyCommandException {
        public NumberInvalid(Object message, Object args) { super(message, args); }
    }

    /** {@code PlayerNotFoundException}. */
    public static class PlayerNotFound extends LegacyCommandException {
        public PlayerNotFound(Object message, Object args) { super(message, args); }
    }

    /** {@code SyntaxErrorException}. */
    public static class SyntaxError extends LegacyCommandException {
        public SyntaxError(Object message, Object args) { super(message, args); }
    }
}
