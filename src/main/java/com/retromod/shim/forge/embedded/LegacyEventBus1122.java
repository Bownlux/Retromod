/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stand-in for the 1.12.2 {@code net.minecraftforge.fml.common.eventhandler.EventBus} that
 * {@code MinecraftForge.EVENT_BUS} used to hold. The host bus is a different type with different
 * listener rules, so registration goes through {@link LegacyForgeEvents}, which understands the
 * 1.12 handler shapes and forwards ordinary events to the host bus.
 */
public final class LegacyEventBus1122 {

    /** Every 1.12 bus reference (game, terrain, ore, FML) collapses onto this one. */
    public static final LegacyEventBus1122 GAME = new LegacyEventBus1122();

    public void register(Object target) {
        LegacyForgeEvents.subscribe(target);
    }

    public void unregister(Object target) {
        LegacyForgeEvents.unsubscribe(target);
    }

    public boolean post(Object event) {
        return LegacyForgeEvents.post(event);
    }
}
