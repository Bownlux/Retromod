/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net;

import com.retromod.loaderapi.fabric.v26.net.LegacyChannels.Flow;
import com.retromod.loaderapi.fabric.v26.net.LegacyChannels.Phase;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Which phase and direction a {@code PacketSender} sends in, so a legacy packet lands in the right registry. */
final class SenderRoles {

    /** A phase and the direction its packets travel. */
    record Role(Phase phase, Flow flow) {}

    private static final Map<Object, Role> KNOWN = Collections.synchronizedMap(new WeakHashMap<>());

    private SenderRoles() {}

    /** Records the role of a sender handed to an old handler, and returns the sender. */
    static Object remember(Object sender, Phase phase, Flow flow) {
        if (sender != null) KNOWN.put(sender, new Role(phase, flow));
        return sender;
    }

    /**
     * The role of {@code sender}. A sender Retromod handed out is known. Any other comes from a
     * Fabric API networking addon, whose class name says the phase and side, as in
     * {@code ClientConfigurationNetworkAddon}. Play towards the client is the common case.
     */
    static Role roleOf(Object sender) {
        Role known = KNOWN.get(sender);
        if (known != null) return known;
        String name = sender.getClass().getSimpleName();
        Phase phase = name.contains("Configuration") ? Phase.CONFIGURATION : Phase.PLAY;
        Flow flow = name.startsWith("Client") ? Flow.SERVERBOUND : Flow.CLIENTBOUND;
        return new Role(phase, flow);
    }
}
