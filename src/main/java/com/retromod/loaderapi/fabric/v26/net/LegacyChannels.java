/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net;

import com.retromod.loaderapi.fabric.v26.net.shared.ClientPlayChannelHandler;
import com.retromod.loaderapi.fabric.v26.net.shared.ServerPlayChannelHandler;
import com.retromod.polyfill.fabric.embedded.FabricNetworkingBridge;
import com.retromod.shim.api.fabric.embedded.RawChannelPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Old raw play channels (an id plus a byte buffer) on 26.x, as one raw payload type per channel id. */
public final class LegacyChannels {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApi");

    /** A connection phase that carries custom payloads. */
    enum Phase { PLAY, CONFIGURATION }

    /** A direction on the wire. */
    enum Flow { CLIENTBOUND, SERVERBOUND }

    /** Who owns a channel id in one registry. */
    enum Owner { RETROMOD, OTHER_MOD }

    private record Slot(Phase phase, Flow flow, String id) {}

    private static final Map<Slot, Owner> SLOTS = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Set<String> LATE_CHANNELS = ConcurrentHashMap.newKeySet();

    private LegacyChannels() {}

    // ---- Server play: the old ServerPlayNetworking raw-channel members ----------------------

    /** Old {@code ServerPlayNetworking.registerGlobalReceiver(Identifier, PlayChannelHandler)}. */
    public static boolean serverRegisterGlobalReceiver(Object id, Object handler) {
        return registerReceiver(Endpoint.SERVER_PLAY, null, id, handler, serverDispatch(handler));
    }

    /** Old {@code ServerPlayNetworking.registerReceiver(ServerGamePacketListenerImpl, Identifier, PlayChannelHandler)}. */
    public static boolean serverRegisterReceiver(Object listener, Object id, Object handler) {
        return registerReceiver(Endpoint.SERVER_PLAY, listener, id, handler, serverDispatch(handler));
    }

    /** Old {@code ServerPlayNetworking.unregisterGlobalReceiver(Identifier)}. */
    public static Object serverUnregisterGlobalReceiver(Object id) {
        return unregisterReceiver(Endpoint.SERVER_PLAY, null, id);
    }

    /** Old {@code ServerPlayNetworking.unregisterReceiver(ServerGamePacketListenerImpl, Identifier)}. */
    public static Object serverUnregisterReceiver(Object listener, Object id) {
        return unregisterReceiver(Endpoint.SERVER_PLAY, listener, id);
    }

    /** Old {@code ServerPlayNetworking.send(ServerPlayer, Identifier, FriendlyByteBuf)}. */
    public static void serverSend(Object player, Object id, Object buf) {
        send(Endpoint.SERVER_PLAY, player, id, HostNet.copyReadable(buf));
    }

    /** Old {@code ServerPlayNetworking.createS2CPacket(Identifier, FriendlyByteBuf)}. */
    public static Object serverCreatePacket(Object id, Object buf) {
        return createPacket(Endpoint.SERVER_PLAY, id, HostNet.copyReadable(buf));
    }

    /** Old {@code ServerPlayNetworking.getServer(ServerGamePacketListenerImpl)}. */
    public static Object serverOf(Object listener) {
        return HostNet.serverOf(listener);
    }

    // ---- Client play: the old ClientPlayNetworking raw-channel members ----------------------

    /** Old {@code ClientPlayNetworking.registerGlobalReceiver(Identifier, PlayChannelHandler)}. */
    public static boolean clientRegisterGlobalReceiver(Object id, Object handler) {
        return registerReceiver(Endpoint.CLIENT_PLAY, null, id, handler, clientDispatch(handler));
    }

    /** Old {@code ClientPlayNetworking.registerReceiver(Identifier, PlayChannelHandler)}. */
    public static boolean clientRegisterReceiver(Object id, Object handler) {
        return registerReceiver(Endpoint.CLIENT_PLAY, NO_LISTENER, id, handler,
                clientDispatch(handler));
    }

    /** Old {@code ClientPlayNetworking.unregisterGlobalReceiver(Identifier)}. */
    public static Object clientUnregisterGlobalReceiver(Object id) {
        return unregisterReceiver(Endpoint.CLIENT_PLAY, null, id);
    }

    /** Old {@code ClientPlayNetworking.unregisterReceiver(Identifier)}. */
    public static Object clientUnregisterReceiver(Object id) {
        return unregisterReceiver(Endpoint.CLIENT_PLAY, NO_LISTENER, id);
    }

    /** Old {@code ClientPlayNetworking.send(Identifier, FriendlyByteBuf)}. */
    public static void clientSend(Object id, Object buf) {
        send(Endpoint.CLIENT_PLAY, null, id, HostNet.copyReadable(buf));
    }

    /** Old {@code ClientPlayNetworking.createC2SPacket(Identifier, FriendlyByteBuf)}. */
    public static Object clientCreatePacket(Object id, Object buf) {
        return createPacket(Endpoint.CLIENT_PLAY, id, HostNet.copyReadable(buf));
    }

    /** Old {@code ClientPlayNetworking.canSend(Identifier)}, kept for the old client bridge. */
    static boolean clientCanSend(Object id) {
        return (Boolean) HostNet.call(Endpoint.CLIENT_PLAY.canSend(false), id);
    }

    /**
     * Routes the old client and server bridges to these channels, so a jar translated before the
     * runtime and one translated with it serve a channel the same way.
     */
    public static void installOldBridgeDelegates() {
        FabricNetworkingBridge.installDelegate(new FabricNetworkingBridge.Delegate() {
            @Override
            public boolean registerServerGlobalReceiver(Object id, Object handler) {
                return serverRegisterGlobalReceiver(id, handler);
            }

            @Override
            public boolean registerClientGlobalReceiver(Object id, Object handler) {
                return clientRegisterGlobalReceiver(id, handler);
            }

            @Override
            public void serverSend(Object player, Object id, Object buf) {
                LegacyChannels.serverSend(player, id, buf);
            }

            @Override
            public void clientSend(Object id, Object buf) {
                LegacyChannels.clientSend(id, buf);
            }
        });
        RawChannelPayloads.installClientDelegate(new RawChannelPayloads.ClientDelegate() {
            @Override
            public boolean registerGlobalReceiver(Object id, Object handler) {
                return clientRegisterGlobalReceiver(id, handler);
            }

            @Override
            public boolean registerReceiver(Object id, Object handler) {
                return clientRegisterReceiver(id, handler);
            }

            @Override
            public void send(Object id, Object buf) {
                clientSend(id, buf);
            }

            @Override
            public boolean canSend(Object id) {
                return clientCanSend(id);
            }
        });
    }

    // ---- Shared by the raw and typed members --------------------------------------------------

    /** Marks a per-connection call on the client, which has a single connection. */
    static final Object NO_LISTENER = new Object();

    /**
     * Claims {@code id} in one registry at startup. A registration already made with Retromod's raw
     * codec, by the old bridge or an earlier jar, is shared. Any other belongs to another mod and is
     * left alone.
     */
    static Owner claim(Phase phase, Flow flow, Object id) {
        return claim(phase, flow, id, false);
    }

    /** {@link #claim} for a send, or for a receiver made while players are connected. */
    static Owner claimDuringPlay(Phase phase, Flow flow, Object id) {
        return claim(phase, flow, id, true);
    }

    private static Owner claim(Phase phase, Flow flow, Object id, boolean duringPlay) {
        return SLOTS.computeIfAbsent(new Slot(phase, flow, id.toString()), slot -> {
            Object registry = HostNet.registry(phase, flow);
            Object type = HostNet.payloadType(id);
            try {
                HostNet.register(registry, type);
                if (duringPlay) reportLateRegistration(id);
                return Owner.RETROMOD;
            } catch (IllegalArgumentException alreadyRegistered) {
                if (RawChannelPayloads.isRawCodec(HostNet.registeredCodec(registry, id))) {
                    return Owner.RETROMOD;
                }
                return Owner.OTHER_MOD;
            }
        });
    }

    /**
     * Notes a payload type registered while players may be connected. Fabric API keeps its payload
     * types in a plain map that connection threads read, so a type added then can make another mod's
     * packet fail to encode in the same instant.
     */
    private static void reportLateRegistration(Object id) {
        boolean first = LATE_CHANNELS.isEmpty();
        if (!LATE_CHANNELS.add(id.toString())) return;
        if (first) {
            LOGGER.info("Registered the payload type for old channel {} when it was first used, not "
                    + "at startup, because nothing registered it earlier. Fabric API does not expect "
                    + "new payload types while players are connected, so another mod's packet sent "
                    + "at that instant can fail. Later channels like this are logged at debug level.",
                    id);
        } else {
            LOGGER.debug("Registered the payload type for old channel {} when it was first used", id);
        }
    }

    /** Channels whose payload type was registered while players may have been connected. */
    static Set<String> lateChannels() {
        return Set.copyOf(LATE_CHANNELS);
    }

    /** Registers a host receiver that hands each payload of {@code id} to {@code dispatch}. */
    static boolean registerReceiver(Endpoint endpoint, Object listener, Object id, Object legacy,
            ReceiverAdapters.Dispatch dispatch) {
        // A per-connection receiver is made while its player is connected; a global one at startup.
        boolean duringPlay = listener != null;
        if (claim(endpoint.phase, endpoint.receives(), id, duringPlay) != Owner.RETROMOD) {
            reportOnce(id + "#receive", "Another mod already registered channel {} with its own "
                    + "payload type, so an old mod's receiver for it was not registered.", id);
            return false;
        }
        // The reply direction too, so a channel used both ways is registered before anyone connects.
        claim(endpoint.phase, endpoint.sends(), id, duringPlay);
        Object type = HostNet.payloadType(id);
        Object adapter = ReceiverAdapters.hostHandler(endpoint, legacy, dispatch);
        if (listener == null) {
            return (Boolean) HostNet.call(endpoint.registerGlobal(), type, adapter);
        }
        return listener == NO_LISTENER
                ? (Boolean) HostNet.call(endpoint.registerOnConnection(), type, adapter)
                : (Boolean) HostNet.call(endpoint.registerOnConnection(), listener, type, adapter);
    }

    /**
     * Unregisters the receiver for {@code id} and returns the old mod's handler. A receiver another
     * mod registered is put back, and the old call gets null as it would for an empty channel.
     */
    static Object unregisterReceiver(Endpoint endpoint, Object listener, Object id) {
        Object key = endpoint == Endpoint.CLIENT_CONFIGURATION && listener == null
                ? HostNet.payloadType(id) : id;
        Object removed;
        if (listener == null) {
            removed = HostNet.call(endpoint.unregisterGlobal(), key);
        } else if (listener == NO_LISTENER) {
            removed = HostNet.call(endpoint.unregisterOnConnection(), id);
        } else {
            removed = HostNet.call(endpoint.unregisterOnConnection(), listener, id);
        }
        if (removed == null) return null;
        Object legacy = ReceiverAdapters.legacyHandlerOf(removed);
        if (legacy != null) return legacy;
        Object type = HostNet.payloadType(id);
        if (listener == null) {
            HostNet.call(endpoint.registerGlobal(), type, removed);
        } else if (listener == NO_LISTENER) {
            HostNet.call(endpoint.registerOnConnection(), type, removed);
        } else {
            HostNet.call(endpoint.registerOnConnection(), listener, type, removed);
        }
        return null;
    }

    /**
     * Sends {@code data} on {@code id}. A channel the other side never registered is dropped with
     * one log line, because the payload would only be discarded there.
     */
    static void send(Endpoint endpoint, Object target, Object id, byte[] data) {
        if (claimDuringPlay(endpoint.phase, endpoint.sends(), id) != Owner.RETROMOD) {
            reportOnce(id + "#send", "Another mod already registered channel {} with its own "
                    + "payload type, so an old mod's packet on it was dropped.", id);
            return;
        }
        if (!canSend(endpoint, target, id)) {
            reportOnce(id + "#unregistered", "The other side has no receiver for channel {}, so "
                    + "an old mod's packet on it was dropped.", id);
            return;
        }
        Object payload = HostNet.newPayload(HostNet.payloadType(id), data);
        if (endpoint.server) {
            HostNet.call(endpoint.send(), target, payload);
        } else {
            HostNet.call(endpoint.send(), payload);
        }
    }

    /** Whether the other side registered {@code id}; a server target is a player or a connection. */
    static boolean canSend(Endpoint endpoint, Object target, Object id) {
        if (!endpoint.server) return (Boolean) HostNet.call(endpoint.canSend(false), id);
        boolean toPlayer = HostNet.isInstance(HostNet.SERVER_PLAYER, target);
        return (Boolean) HostNet.call(endpoint.canSend(toPlayer), target, id);
    }

    /** The packet that carries {@code data} on {@code id}, without sending it. */
    static Object createPacket(Endpoint endpoint, Object id, byte[] data) {
        return HostNet.call(endpoint.createPacket(),
                payloadOrDiscarded(endpoint.phase, endpoint.sends(), id, data));
    }

    /**
     * A raw payload for {@code data} on {@code id}. Old Fabric API built a packet whatever the
     * channel, so on a channel another mod owns this returns a payload the other side reads and
     * ignores: the other mod's codec cannot encode Retromod's payload, and failing there would
     * disconnect the player.
     */
    static Object payloadOrDiscarded(Phase phase, Flow flow, Object id, byte[] data) {
        if (claimDuringPlay(phase, flow, id) == Owner.RETROMOD) {
            return HostNet.newPayload(HostNet.payloadType(id), data);
        }
        reportOnce(id + "#create", "Another mod already registered channel {} with its own payload "
                + "type, so an old mod's packet on it was built empty and the other side ignores it.",
                id);
        return HostNet.discardedPayload();
    }

    /** Logs {@code message} once per key, so a busy channel does not flood the log. */
    static void reportOnce(String key, String message, Object id) {
        if (REPORTED.add(key)) LOGGER.info(message, id);
    }

    // ---- Dispatch to the old handlers --------------------------------------------------------

    /** Calls an old server channel handler with the arguments it used to get. */
    private static ReceiverAdapters.Dispatch serverDispatch(Object handler) {
        return (payload, context) -> {
            Object player = HostNet.call(Endpoint.SERVER_PLAY.context("player",
                    HostNet.SERVER_PLAYER), context);
            Object sender = SenderRoles.remember(HostNet.call(Endpoint.SERVER_PLAY.context(
                    "responseSender", HostNet.SENDER), context), Phase.PLAY, Flow.CLIENTBOUND);
            Object server = HostNet.call(Endpoint.SERVER_PLAY.context("server",
                    HostNet.MINECRAFT_SERVER), context);
            Object buf = HostNet.newBuf(ReceiverAdapters.bytesOf(payload));
            Object listener = HostNet.connectionOf(player);
            if (handler instanceof ServerPlayChannelHandler typed) {
                typed.receiveServerPlayChannel(server, player, listener, buf, sender);
            } else {
                ReceiverAdapters.invokeReceive(handler, server, player, listener, buf, sender);
            }
        };
    }

    /** Calls an old client channel handler with the arguments it used to get. */
    private static ReceiverAdapters.Dispatch clientDispatch(Object handler) {
        return (payload, context) -> {
            Object client = HostNet.call(Endpoint.CLIENT_PLAY.context("client", HostNet.MINECRAFT),
                    context);
            Object sender = SenderRoles.remember(HostNet.call(Endpoint.CLIENT_PLAY.context(
                    "responseSender", HostNet.SENDER), context), Phase.PLAY, Flow.SERVERBOUND);
            Object buf = HostNet.newBuf(ReceiverAdapters.bytesOf(payload));
            Object listener = HostNet.clientConnection(client);
            if (handler instanceof ClientPlayChannelHandler typed) {
                typed.receiveClientPlayChannel(client, listener, buf, sender);
            } else {
                ReceiverAdapters.invokeReceive(handler, client, listener, buf, sender);
            }
        };
    }
}
