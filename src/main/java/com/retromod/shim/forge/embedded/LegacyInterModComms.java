/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stands in for 1.12.2's {@code FMLInterModComms}, which mods used to hand integration data to
 * other 1.12 mods such as Waila or The One Probe. Those mods are not running here, so messages are
 * accepted and dropped, and the mod continues its init. Reference parameters are {@code Object}
 * because calls on stand-ins are erased to that shape.
 */
public final class LegacyInterModComms {
    private LegacyInterModComms() {}

    public static boolean sendMessage(Object modId, Object key, Object value) { return false; }
    public static boolean sendFunctionMessage(Object modId, Object key, Object functionClass) { return false; }
    public static void sendRuntimeMessage(Object sourceMod, Object modId, Object key, Object value) {
        // No 1.12 receiver exists on this host.
    }
}
