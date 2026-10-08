/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

/**
 * Stand-in for the 1.12.2 {@code SimpleNetworkWrapper}. Registration succeeds and sends are
 * dropped, so a mod loads and runs its server logic, but client and server never exchange the
 * mod's own packets.
 */
public final class LegacySimpleNetworkWrapper {

    private final String channel;

    public LegacySimpleNetworkWrapper(Object channel) {
        this.channel = String.valueOf(channel);
        LegacyForgeEvents.note("network channel " + this.channel, null);
    }

    public void registerMessage(Object handler, Object message, int discriminator, Object side) {
    }

    public void sendTo(Object message, Object player) {
    }

    public void sendToServer(Object message) {
    }

    public void sendToAll(Object message) {
    }

    public void sendToAllAround(Object message, Object point) {
    }

    public void sendToAllTracking(Object message, Object target) {
    }

    public void sendToDimension(Object message, int dimension) {
    }

    @Override
    public String toString() {
        return "LegacySimpleNetworkWrapper[" + channel + "]";
    }
}
