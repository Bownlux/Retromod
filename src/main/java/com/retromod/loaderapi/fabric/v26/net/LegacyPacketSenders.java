/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net;

import com.retromod.loaderapi.fabric.v26.HostLink.Member;
import com.retromod.loaderapi.fabric.v26.net.LegacyChannels.Owner;

import static com.retromod.loaderapi.fabric.v26.net.HostNet.CHANNEL_FUTURE_LISTENER;
import static com.retromod.loaderapi.fabric.v26.net.HostNet.FRIENDLY_BUF;
import static com.retromod.loaderapi.fabric.v26.net.HostNet.IDENTIFIER;
import static com.retromod.loaderapi.fabric.v26.net.HostNet.LOGIN_SENDER;
import static com.retromod.loaderapi.fabric.v26.net.HostNet.PACKET;
import static com.retromod.loaderapi.fabric.v26.net.HostNet.PAYLOAD;
import static com.retromod.loaderapi.fabric.v26.net.HostNet.SENDER;

/** The old {@code PacketSender} members 26.x removed: raw channel sends and the old listener types. */
public final class LegacyPacketSenders {

    private static final String RAW = "(L" + IDENTIFIER + ";L" + FRIENDLY_BUF + ";";

    private LegacyPacketSenders() {}

    /** Old {@code PacketSender.sendPacket(Identifier, FriendlyByteBuf)}. */
    public static void sendRaw(Object sender, Object id, Object buf) {
        sendRawWithListener(sender, id, buf, null);
    }

    /** Old {@code PacketSender.sendPacket(Identifier, FriendlyByteBuf, listener)}, either listener type. */
    public static void sendRawWithListener(Object sender, Object id, Object buf, Object listener) {
        Object hostListener = SendListeners.toHost(listener);
        // Login still sends raw channels on 26.x, through LoginPacketSender's own members.
        if (HostNet.isInstance(LOGIN_SENDER, sender)) {
            if (hostListener == null) {
                HostNet.call(Member.virtual(LOGIN_SENDER, "sendPacket", RAW + ")V"), sender, id, buf);
            } else {
                HostNet.call(Member.virtual(LOGIN_SENDER, "sendPacket",
                        RAW + "L" + CHANNEL_FUTURE_LISTENER + ";)V"), sender, id, buf, hostListener);
            }
            return;
        }
        Object payload = payloadFor(sender, id, HostNet.copyReadable(buf));
        if (payload != null) sendPayload(sender, payload, hostListener);
    }

    /** Old {@code PacketSender.createPacket(Identifier, FriendlyByteBuf)}. */
    public static Object createRawPacket(Object sender, Object id, Object buf) {
        if (HostNet.isInstance(LOGIN_SENDER, sender)) {
            return HostNet.call(Member.virtual(LOGIN_SENDER, "createPacket",
                    RAW + ")L" + PACKET + ";"), sender, id, buf);
        }
        SenderRoles.Role role = SenderRoles.roleOf(sender);
        Object payload = LegacyChannels.payloadOrDiscarded(role.phase(), role.flow(), id,
                HostNet.copyReadable(buf));
        return HostNet.call(Member.virtual(SENDER, "createPacket",
                "(L" + PAYLOAD + ";)L" + PACKET + ";"), sender, payload);
    }

    /** Old {@code PacketSender.sendPacket(CustomPacketPayload, listener)}, either listener type. */
    public static void sendPayloadWithListener(Object sender, Object payload, Object listener) {
        sendPayload(sender, payload, SendListeners.toHost(listener));
    }

    /** Old {@code PacketSender.sendPacket(Packet, listener)}, either listener type. */
    public static void sendPacketWithListener(Object sender, Object packet, Object listener) {
        Object hostListener = SendListeners.toHost(listener);
        if (hostListener == null) {
            HostNet.call(Member.virtual(SENDER, "sendPacket", "(L" + PACKET + ";)V"), sender, packet);
        } else {
            HostNet.call(Member.virtual(SENDER, "sendPacket",
                    "(L" + PACKET + ";L" + CHANNEL_FUTURE_LISTENER + ";)V"), sender, packet,
                    hostListener);
        }
    }

    /**
     * A raw payload for {@code id} in the sender's phase and direction, or null when another mod
     * owns the channel there.
     */
    static Object payloadFor(Object sender, Object id, byte[] data) {
        SenderRoles.Role role = SenderRoles.roleOf(sender);
        if (LegacyChannels.claimDuringPlay(role.phase(), role.flow(), id) != Owner.RETROMOD) {
            LegacyChannels.reportOnce(id + "#send", "Another mod already registered channel {} "
                    + "with its own payload type, so an old mod's packet on it was dropped.", id);
            return null;
        }
        return HostNet.newPayload(HostNet.payloadType(id), data);
    }

    /** Sends a payload through a host sender, with a host listener or none. */
    static void sendPayload(Object sender, Object payload, Object hostListener) {
        if (hostListener == null) {
            HostNet.call(Member.virtual(SENDER, "sendPacket", "(L" + PAYLOAD + ";)V"), sender, payload);
        } else {
            HostNet.call(Member.virtual(SENDER, "sendPacket",
                    "(L" + PAYLOAD + ";L" + CHANNEL_FUTURE_LISTENER + ";)V"), sender, payload,
                    hostListener);
        }
    }
}
