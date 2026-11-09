/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Stand-in for Forge 1.20.1's {@code EventNetworkChannel}, backed by the event channel Forge 26.x builds. */
public final class LegacyEventChannel {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final Map<String, LegacyEventChannel> CHANNELS = new ConcurrentHashMap<>();
    // Forge 26.x's NetworkDirection, and its constant for each old direction, resolved on the first packet.
    private static volatile Class<?> hostDirections;
    private static final Map<NetworkShim.NetworkDirection, Object> HOST_DIRECTIONS = new ConcurrentHashMap<>();

    private final Object channelId;
    private final Object hostChannel;
    private final NetworkShim.ForgeChannels forge;
    private final List<Consumer<Object>> listeners = new CopyOnWriteArrayList<>();
    // The event kinds each listener's lambda refused, so it is not handed them again.
    private final Map<Consumer<Object>, Set<Class<?>>> refused = new ConcurrentHashMap<>();

    private LegacyEventChannel(Object channelId, Object hostChannel, NetworkShim.ForgeChannels forge) {
        this.channelId = channelId;
        this.hostChannel = hostChannel;
        this.forge = forge;
    }

    /** {@code NetworkRegistry.newEventChannel}: one channel per id, as Forge allowed only one. */
    public static LegacyEventChannel open(Object channelId, Supplier<String> networkProtocolVersion,
            Predicate<String> clientAcceptedVersions, Predicate<String> serverAcceptedVersions) {
        return CHANNELS.computeIfAbsent(String.valueOf(channelId), key -> {
            String version = networkProtocolVersion == null ? "1" : networkProtocolVersion.get();
            NetworkShim.ForgeChannels forge = NetworkShim.forgeChannels();
            if (forge == null) {
                // NeoForge has payloads instead, and Retromod does not carry an event channel over them.
                LOGGER.warn("Retromod did not connect the Forge event channel {} to this loader's networking, "
                        + "so messages on it are not sent or received.", channelId);
                return new LegacyEventChannel(channelId, null, null);
            }
            LegacyEventChannel channel = new LegacyEventChannel(channelId, forge.openEvents(channelId, version), forge);
            channel.listenOnHost();
            return channel;
        });
    }

    /** {@code addListener(Consumer)}: the listener gets each payload whose kind its lambda takes. */
    @SuppressWarnings("unchecked")
    public void addListener(Consumer<?> listener) {
        listeners.add((Consumer<Object>) listener);
    }

    /** {@code isRemotePresent(connection)}: whether the other side opened this channel too. */
    public boolean isRemotePresent(Object connection) {
        if (hostChannel == null) return false;
        return Boolean.TRUE.equals(call(hostChannel, "isRemotePresent", connection));
    }

    /** {@code NetworkDirection.buildPacket(pair, id)}: a packet carrying the pair's buffer on that channel. */
    public static CustomPacket buildPacket(NetworkShim.NetworkDirection direction, Object payloadAndIndex,
            Object channelId) {
        LegacyEventChannel channel = CHANNELS.get(String.valueOf(channelId));
        if (channel == null) {
            throw new IllegalStateException("Retromod has no open Forge event channel " + channelId
                    + " to build a packet on.");
        }
        // The mod sends the packet itself, so there is no send to skip: a null or empty packet would fail later,
        // inside Minecraft's connection, with less to go on than this.
        if (channel.hostChannel == null) {
            throw new IllegalStateException("Retromod did not connect the Forge event channel " + channelId
                    + " to this loader's networking, so it cannot build a packet on it.");
        }
        Object buffer = call(payloadAndIndex, "getLeft");
        Object packet = channel.hostPacket(direction, buffer);
        return () -> packet;
    }

    private Object hostPacket(NetworkShim.NetworkDirection direction, Object buffer) {
        try {
            Object hostDirection = hostDirection(direction);
            return NetworkShim.PayloadBridge.method(hostDirection.getClass(), "buildPacket", 2, false)
                    .invoke(hostDirection, hostChannel, buffer);
        } catch (InvocationTargetException e) {
            throw rethrown(e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build a packet for Forge event channel "
                    + channelId, e);
        }
    }

    // Forge 26.x keeps the old direction names as constants, each building its own packet kind.
    private Object hostDirection(NetworkShim.NetworkDirection direction) throws ReflectiveOperationException {
        Object known = HOST_DIRECTIONS.get(direction);
        if (known != null) return known;
        Class<?> directions = hostDirections;
        if (directions == null) {
            directions = Class.forName("net.minecraftforge.network.NetworkDirection", false,
                    hostChannel.getClass().getClassLoader());
            hostDirections = directions;
        }
        Object constant = directions.getField(direction.name()).get(null);
        HOST_DIRECTIONS.put(direction, constant);
        return constant;
    }

    private void listenOnHost() {
        Consumer<Object> fromHost = this::deliver;
        call(hostChannel, "addListener", fromHost);
    }

    /** Hands one received payload to every listener, as the old event of its direction. */
    void deliver(Object hostEvent) {
        Object context = call(hostEvent, "getSource");
        NetworkShim.Context source = new NetworkShim.Context(context, forge);
        Object payload = call(hostEvent, "getPayload");
        int loginIndex = (Integer) call(hostEvent, "getLoginIndex");
        // A payload read on the server came from a client, as ClientCustomPayloadEvent did.
        NetworkEvent event = Boolean.TRUE.equals(call(context, "isServerSide"))
                ? new ClientCustomPayloadEvent(payload, source, loginIndex)
                : new ServerCustomPayloadEvent(payload, source, loginIndex);
        for (Consumer<Object> listener : listeners) {
            Set<Class<?>> kinds = refused.get(listener);
            if (kinds != null && kinds.contains(event.getClass())) continue;
            try {
                listener.accept(event);
            } catch (ClassCastException wrongKind) {
                if (!refusedAtEntry(wrongKind, listener)) throw wrongKind;
                refused.computeIfAbsent(listener, ignored -> ConcurrentHashMap.newKeySet()).add(event.getClass());
            }
        }
    }

    // The old bus gave a listener only the event type its lambda named. A lambda casts the event before its body
    // runs, in a frame the stack trace hides, so a cast failure seen first in this class is that check.
    private static boolean refusedAtEntry(ClassCastException failure, Object listener) {
        StackTraceElement[] trace = failure.getStackTrace();
        if (trace.length == 0) return false;
        String top = trace[0].getClassName();
        return top.equals(LegacyEventChannel.class.getName()) || top.equals(listener.getClass().getName());
    }

    private static Object call(Object target, String name, Object... args) {
        try {
            return NetworkShim.PayloadBridge.method(target.getClass(), name, args.length, false).invoke(target, args);
        } catch (InvocationTargetException e) {
            throw rethrown(e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not call " + name + " on "
                    + target.getClass().getName() + " for a Forge event channel", e);
        }
    }

    private static RuntimeException rethrown(InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(cause);
    }

    static void resetForTesting() {
        CHANNELS.clear();
        hostDirections = null;
        HOST_DIRECTIONS.clear();
    }

    static void useHostDirectionsForTesting(Class<?> directions) {
        hostDirections = directions;
        HOST_DIRECTIONS.clear();
    }

    /** Stand-in for Forge 1.20.1's {@code NetworkEvent}: the payload, its context, and the login index. */
    public static class NetworkEvent {
        private final Object payload;
        private final NetworkShim.Context source;
        private final int loginIndex;

        NetworkEvent(Object payload, NetworkShim.Context source, int loginIndex) {
            this.payload = payload;
            this.source = source;
            this.loginIndex = loginIndex;
        }

        /** The received buffer; Object here, cast back at the mod's call site. */
        public Object getPayload() {
            return payload;
        }

        public Supplier<NetworkShim.Context> getSource() {
            return () -> source;
        }

        public int getLoginIndex() {
            return loginIndex;
        }
    }

    /** A payload the server sent, read on the client. */
    public static final class ServerCustomPayloadEvent extends NetworkEvent {
        ServerCustomPayloadEvent(Object payload, NetworkShim.Context source, int loginIndex) {
            super(payload, source, loginIndex);
        }
    }

    /** A payload a client sent, read on the server. */
    public static final class ClientCustomPayloadEvent extends NetworkEvent {
        ClientCustomPayloadEvent(Object payload, NetworkShim.Context source, int loginIndex) {
            super(payload, source, loginIndex);
        }
    }

    /** Stand-in for Forge 1.20.1's {@code ICustomPacket}; {@code getThis} is the vanilla packet, cast back by the mod. */
    public interface CustomPacket {
        Object getThis();
    }
}
