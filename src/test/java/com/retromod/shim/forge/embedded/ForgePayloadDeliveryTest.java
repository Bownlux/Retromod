/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forge {@code SimpleChannel} messages delivered through NeoForge payloads. The host types are
 * stand-ins with the same method names and shapes, since Retromod reaches NeoForge only by
 * reflection: one payload type per channel, a VarInt message id, then the mod's own encoding.
 */
class ForgePayloadDeliveryTest {

    // ---- stand-ins for the host types -------------------------------------------------

    public interface Payload { Object type(); }

    public record PayloadType(Object id) {}

    public interface Codec {
        Object decode(Object buffer);
        void encode(Object buffer, Object value);
    }

    public interface Handler { void handle(Object payload, Object context); }

    public enum Flow { SERVERBOUND, CLIENTBOUND }

    public interface PayloadContext {
        CompletableFuture<Void> enqueueWork(Runnable work);
        Object player();
        Flow flow();
        default Object connection() { return "connection"; }
    }

    public enum Thread { MAIN, NETWORK }

    public static final class Buffer {
        final Deque<Object> values = new ArrayDeque<>();
        public int readVarInt() { return (Integer) values.pop(); }
        public Buffer writeVarInt(int value) { values.add(value); return this; }
    }

    public static final class Registrar {
        boolean optional;
        Thread thread;
        Object type;
        Codec codec;
        Handler handler;
        public Registrar optional() { optional = true; return this; }
        public Registrar executesOn(Thread value) { thread = value; return this; }
        public Registrar playBidirectional(Object type, Object codec, Object handler) {
            this.type = type;
            this.codec = (Codec) codec;
            this.handler = (Handler) handler;
            return this;
        }
    }

    public static final class Event {
        final Registrar registrar = new Registrar();
        String version;
        public Registrar registrar(String version) { this.version = version; return registrar; }
    }

    public static final class Distributor {
        static final List<Object[]> sent = new ArrayList<>();
        public static void sendToPlayer(Object player, Payload payload, Payload... more) {
            sent.add(new Object[]{"player", player, payload});
        }
        public static void sendToServer(Payload payload, Payload... more) {
            sent.add(new Object[]{"server", null, payload});
        }
    }

    // ---- the mod's side ----------------------------------------------------------------

    record Ping(int value) {}

    private final List<Object[]> handled = new ArrayList<>();
    private Event event;
    private NetworkShim.SimpleChannelWrapper channel;

    @BeforeEach
    void registerChannel() {
        NetworkShim.resetForTesting();
        Distributor.sent.clear();
        channel = (NetworkShim.SimpleChannelWrapper) NetworkShim.newSimpleChannel(
                "testmod:main", () -> "3", null, null);
        channel.registerMessage(7, Ping.class,
                (ping, buffer) -> ((Buffer) buffer).values.add(ping.value()),
                buffer -> new Ping((Integer) ((Buffer) buffer).values.pop()),
                (ping, context) -> {
                    NetworkShim.Context forge = (NetworkShim.Context) ((Supplier<?>) context).get();
                    handled.add(new Object[]{ping, forge.getSender(), forge.getDirection()});
                });
        event = new Event();
        NetworkShim.registerPayloads(event, new NetworkShim.PayloadBridge(
                getClass().getClassLoader(), Payload.class, PayloadType.class, Codec.class,
                Handler.class, PayloadContext.class, Thread.class, Distributor.class, null));
    }

    @AfterEach
    void reset() {
        NetworkShim.resetForTesting();
    }

    @Test
    @DisplayName("each Forge channel registers as one optional, main-thread, bidirectional payload")
    void registersTheChannel() {
        assertEquals("3", event.version, "the channel's protocol version is the payload version");
        assertTrue(event.registrar.optional);
        assertEquals(Thread.MAIN, event.registrar.thread);
        assertEquals(new PayloadType("testmod:main"), event.registrar.type);
    }

    @Test
    @DisplayName("a message sent to a player round-trips through the codec to the Forge handler")
    void messageRoundTrips() {
        channel.send(NetworkShim.PacketDistributor.PLAYER.with(() -> "steve"), new Ping(42));

        Object[] send = Distributor.sent.get(0);
        assertEquals("player", send[0]);
        assertEquals("steve", send[1]);
        Payload payload = (Payload) send[2];
        assertEquals(new PayloadType("testmod:main"), payload.type());

        Buffer buffer = new Buffer();
        event.registrar.codec.encode(buffer, payload);
        assertEquals(7, buffer.values.peek(), "the Forge message id goes first");
        Object decoded = event.registrar.codec.decode(buffer);
        assertTrue(buffer.values.isEmpty(), "the mod's decoder must read what its encoder wrote");

        event.registrar.handler.handle(decoded, context(Flow.SERVERBOUND, "steve"));
        assertEquals(1, handled.size());
        assertEquals(new Ping(42), handled.get(0)[0]);
        assertEquals("steve", handled.get(0)[1], "getSender is the player on the server");
        assertEquals(NetworkShim.NetworkDirection.PLAY_TO_SERVER, handled.get(0)[2]);
    }

    @Test
    @DisplayName("on the client, getSender is null as in Forge")
    void clientHasNoSender() {
        Buffer buffer = new Buffer();
        buffer.writeVarInt(7);
        buffer.values.add(5);
        Object decoded = event.registrar.codec.decode(buffer);

        event.registrar.handler.handle(decoded, context(Flow.CLIENTBOUND, "local"));
        assertNull(handled.get(0)[1]);
        assertEquals(NetworkShim.NetworkDirection.PLAY_TO_CLIENT, handled.get(0)[2]);
    }

    @Test
    @DisplayName("sendToServer uses NeoForge's server send, and an unknown id names the channel")
    void sendToServerAndUnknownIds() {
        channel.sendToServer(new Ping(1));
        assertEquals("server", Distributor.sent.get(0)[0]);

        Buffer buffer = new Buffer();
        buffer.writeVarInt(99);
        IllegalStateException unknown = assertThrows(IllegalStateException.class,
                () -> event.registrar.codec.decode(buffer));
        assertTrue(unknown.getMessage().contains("testmod:main"), unknown.getMessage());
    }

    @Test
    @DisplayName("a message type with no registration is refused with its class name")
    void unregisteredMessageIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> channel.sendToServer("not a registered message"));
        assertTrue(refused.getMessage().contains("java.lang.String"), refused.getMessage());
    }

    public static final class ClientEvent {
        final List<Object[]> registered = new ArrayList<>();
        public void register(Object type, Object handler) { registered.add(new Object[]{type, handler}); }
    }

    @Test
    @DisplayName("from 1.21.11 the client side of each channel registers through the client event")
    void clientHandlersRegister() {
        ClientEvent client = new ClientEvent();
        NetworkShim.registerClientHandlers(client);

        assertEquals(1, client.registered.size());
        assertEquals(new PayloadType("testmod:main"), client.registered.get(0)[0]);
        assertSame(event.registrar.handler, client.registered.get(0)[1],
                "the client gets the same handler the common registration used");
    }

    private static PayloadContext context(Flow flow, Object player) {
        return new PayloadContext() {
            public CompletableFuture<Void> enqueueWork(Runnable work) {
                work.run();
                return CompletableFuture.completedFuture(null);
            }
            public Object player() { return player; }
            public Flow flow() { return flow; }
        };
    }
}
