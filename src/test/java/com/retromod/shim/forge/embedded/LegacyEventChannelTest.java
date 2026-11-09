/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** An old event channel on Forge 26.x: one host event channel, and each listener gets the old event kind it names. */
class LegacyEventChannelTest {

    // ---- a stand-in for Forge 26.x's event channel API, with the same member names --------------

    public static final class Builder {
        static Builder last;
        Object id;
        int version;
        boolean optional;
        HostChannel channel;

        public static Builder named(Object id) {
            last = new Builder();
            last.id = id;
            return last;
        }

        public Builder networkProtocolVersion(int version) {
            this.version = version;
            return this;
        }

        public Builder optional() {
            optional = true;
            return this;
        }

        public HostChannel eventNetworkChannel() {
            channel = new HostChannel();
            return channel;
        }
    }

    public static final class HostChannel {
        final List<Consumer<Object>> listeners = new ArrayList<>();

        @SuppressWarnings("unchecked")
        public HostChannel addListener(Consumer<?> listener) {
            listeners.add((Consumer<Object>) listener);
            return this;
        }

        public boolean isRemotePresent(Object connection) {
            return "modded".equals(connection);
        }

        void receive(Object payload, boolean serverSide) {
            HostEvent event = new HostEvent(payload, new HostContext(serverSide), 0);
            listeners.forEach(listener -> listener.accept(event));
        }
    }

    public record HostEvent(Object getPayload, HostContext getSource, int getLoginIndex) {}

    public static final class HostContext {
        final boolean serverSide;
        boolean handled;

        HostContext(boolean serverSide) {
            this.serverSide = serverSide;
        }

        public boolean isServerSide() {
            return serverSide;
        }

        public Object getSender() {
            return "steve";
        }

        public void setPacketHandled(boolean handled) {
            this.handled = handled;
        }
    }

    /** Forge 26.x's NetworkDirection: each constant builds a packet for a channel and a message. */
    public static final class HostDirections {
        public static final HostDirections PLAY_TO_SERVER = new HostDirections("serverbound");
        private final String flow;

        HostDirections(String flow) {
            this.flow = flow;
        }

        public Object buildPacket(Object channel, Object message) {
            return List.of(flow, channel, message);
        }
    }

    /** commons-lang3's Pair, as JEI and Architectury pass it: the buffer and a login index. */
    public record Pair(Object getLeft, Integer getRight) {}

    // ---- the mod's side ----------------------------------------------------------------

    private final List<String> heard = new ArrayList<>();

    @BeforeEach
    void forgeHost() {
        LegacyEventChannel.resetForTesting();
        NetworkShim.resetForTesting();
        NetworkShim.useForgeChannelsForTesting(new NetworkShim.ForgeChannels(Builder.class, Object.class,
                Object.class, Object.class));
    }

    @AfterEach
    void reset() {
        LegacyEventChannel.resetForTesting();
        NetworkShim.resetForTesting();
    }

    @Test
    @DisplayName("newEventChannel opens one optional Forge event channel under the old id and version")
    void opensAHostChannel() {
        LegacyEventChannel channel = LegacyEventChannel.open("jei:channel", () -> "7", null, null);
        assertSame(channel, LegacyEventChannel.open("jei:channel", () -> "7", null, null), "one channel per id");
        assertEquals("jei:channel", Builder.last.id);
        assertEquals(7, Builder.last.version);
        assertTrue(Builder.last.optional, "a peer without the channel was accepted, as acceptMissingOr did");
        assertTrue(channel.isRemotePresent("modded"));
    }

    @Test
    @DisplayName("Each listener gets only the direction its lambda names, and a base listener gets both")
    void listenersGetTheirKind() {
        LegacyEventChannel channel = LegacyEventChannel.open("jei:channel", () -> "1", null, null);
        Consumer<LegacyEventChannel.ServerCustomPayloadEvent> fromServer = event -> heard.add("client read " + event.getPayload());
        Consumer<LegacyEventChannel.ClientCustomPayloadEvent> fromClient = event -> {
            heard.add("server read " + event.getPayload());
            NetworkShim.Context context = event.getSource().get();
            heard.add("sent by " + context.getSender());
            context.setPacketHandled(true);
        };
        Consumer<LegacyEventChannel.NetworkEvent> any = event -> heard.add("any " + event.getPayload());
        channel.addListener(fromServer);
        channel.addListener(fromClient);
        channel.addListener(any);

        HostChannel host = Builder.last.channel;
        host.receive("cheat", true);
        host.receive("result", false);
        host.receive("cheat again", true);
        assertEquals(List.of("server read cheat", "sent by steve", "any cheat", "client read result", "any result",
                "server read cheat again", "sent by steve", "any cheat again"), heard);
    }

    @Test
    @DisplayName("A ClassCastException thrown inside a listener's own code still reaches the game")
    void listenerFailuresPropagate() {
        LegacyEventChannel channel = LegacyEventChannel.open("jei:channel", () -> "1", null, null);
        Consumer<LegacyEventChannel.ClientCustomPayloadEvent> broken = event -> {
            Object payload = event.getPayload();
            heard.add(((Integer) payload).toString());
        };
        channel.addListener(broken);
        assertThrows(ClassCastException.class, () -> Builder.last.channel.receive("not a number", true));
    }

    @Test
    @DisplayName("buildPacket builds the host packet for the channel opened under the id, with the pair's buffer")
    void buildsHostPackets() {
        LegacyEventChannel.open("jei:channel", () -> "1", null, null);
        LegacyEventChannel.useHostDirectionsForTesting(HostDirections.class);
        Object packet = LegacyEventChannel.buildPacket(NetworkShim.NetworkDirection.PLAY_TO_SERVER,
                new Pair("buffer", 0), "jei:channel").getThis();
        assertEquals(List.of("serverbound", Builder.last.channel, "buffer"), packet);
        assertEquals(List.of("serverbound", Builder.last.channel, "again"), LegacyEventChannel.buildPacket(
                NetworkShim.NetworkDirection.PLAY_TO_SERVER, new Pair("again", 0), "jei:channel").getThis(),
                "a second packet goes the same way");
        assertThrows(IllegalStateException.class, () -> LegacyEventChannel.buildPacket(
                NetworkShim.NetworkDirection.PLAY_TO_SERVER, new Pair("buffer", 0), "other:channel"));
    }

    @Test
    @DisplayName("Without Forge's channel API the channel opens without a host and its listeners stay idle")
    void withoutForgeChannels() {
        NetworkShim.useForgeChannelsForTesting(null);
        LegacyEventChannel channel = LegacyEventChannel.open("jei:channel", () -> "1", null, null);
        channel.addListener(event -> heard.add("never"));
        assertFalse(channel.isRemotePresent("modded"));
        assertTrue(heard.isEmpty());
        // The mod sends what buildPacket returns itself, so it gets a failure that names the channel.
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> LegacyEventChannel.buildPacket(
                NetworkShim.NetworkDirection.PLAY_TO_SERVER, new Pair("buffer", 0), "jei:channel"));
        assertTrue(failure.getMessage().contains("did not connect the Forge event channel jei:channel"),
                failure.getMessage());
    }
}
