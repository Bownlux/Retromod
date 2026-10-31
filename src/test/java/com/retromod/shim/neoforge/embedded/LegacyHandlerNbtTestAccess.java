/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge.embedded;

import java.lang.reflect.Method;

/** Stand-in handler classes for {@link LegacyHandlerNbt}'s override lookup, which is package-private. */
public final class LegacyHandlerNbtTestAccess {

    private LegacyHandlerNbtTestAccess() {}

    /** The lookup, with {@link Host} in the place of NeoForge's ItemStackHandler. */
    public static Method override(Object handler, String name, int arity) {
        return LegacyHandlerNbt.override(handler, Host.class.getName(), name, arity);
    }

    /** Named like Minecraft's, which is how the lookup recognizes the old parameter. */
    public interface HolderLookup {
        interface Provider {}
    }

    public static class Host {
        public Object serializeNBT(HolderLookup.Provider provider) {
            return "host";
        }
    }

    public static class PlainHandler extends Host {}

    public static class OwnHandler extends Host {
        @Override
        public Object serializeNBT(HolderLookup.Provider provider) {
            return "own";
        }
    }
}
