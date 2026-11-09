/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.event;

/** Forge 26.2's isAttack, isUseItem and isPickBlock of the key mapping event, which Forge 26.3 split into one event per key. */
public final class KeyMappingTriggers {

    // Matched by name, so this class needs nothing from Forge and links on any host.
    private static final String TRIGGERED = "net.minecraftforge.client.event.InputEvent$InteractionKeyMappingTriggered$";

    private KeyMappingTriggers() {}

    /** {@code event.isAttack()}. */
    public static boolean isAttack(Object event) {
        return is(event, "Attack");
    }

    /** {@code event.isUseItem()}. */
    public static boolean isUseItem(Object event) {
        return is(event, "UseItem");
    }

    /** {@code event.isPickBlock()}. */
    public static boolean isPickBlock(Object event) {
        return is(event, "PickBlock");
    }

    // Each key's event is a final class, so its name answers the question.
    private static boolean is(Object event, String key) {
        return event.getClass().getName().equals(TRIGGERED + key);
    }
}
