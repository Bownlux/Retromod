/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import com.retromod.shim.forge.embedded.ForgePayloadDeliveryTest.Buffer;
import com.retromod.shim.forge.embedded.ForgePayloadDeliveryTest.Codec;
import com.retromod.shim.forge.embedded.ForgePayloadDeliveryTest.Distributor;
import com.retromod.shim.forge.embedded.ForgePayloadDeliveryTest.Event;
import com.retromod.shim.forge.embedded.ForgePayloadDeliveryTest.Flow;
import com.retromod.shim.forge.embedded.ForgePayloadDeliveryTest.Handler;
import com.retromod.shim.forge.embedded.ForgePayloadDeliveryTest.Payload;
import com.retromod.shim.forge.embedded.ForgePayloadDeliveryTest.PayloadContext;
import com.retromod.shim.forge.embedded.ForgePayloadDeliveryTest.PayloadType;
import com.retromod.shim.forge.embedded.ForgePayloadDeliveryTest.Thread;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forge 1.20.2's channel networking delivered through NeoForge payloads: a channel built with
 * {@code ChannelBuilder}, messages numbered in the order they are added, and handlers that take the
 * context itself, as Forge 1.21.1 mods write them.
 */
class ForgeChannelBuilderDeliveryTest {

    record Ping(int value) {}

    record Pong(String text) {}

    /** A Forge 1.20.2 {@code StreamCodec}: encode and decode take the buffer first. */
    public static final class PongCodec {
        public void encode(Object buffer, Object pong) {
            ((Buffer) buffer).values.add(((Pong) pong).text());
        }

        public Object decode(Object buffer) {
            return new Pong((String) ((Buffer) buffer).values.pop());
        }
    }

    private final List<Object[]> handled = new ArrayList<>();
    private Event event;
    private NetworkShim.SimpleChannelWrapper channel;

    @BeforeEach
    void registerChannel() {
        NetworkShim.resetForTesting();
        Distributor.sent.clear();
        // As a Forge 1.21.1 mod builds its channel, after Retromod moved the calls to the shim.
        channel = NetworkShim.ChannelBuilder.named("testmod:main")
                .networkProtocolVersion(3)
                .acceptedVersions(NetworkShim.VersionTest.exact(3))
                .optional()
                .simpleChannel();
        channel.messageBuilder(Ping.class, NetworkShim.NetworkDirection.PLAY_TO_SERVER)
                .encoder((ping, buffer) -> ((Buffer) buffer).values.add(ping.value()))
                .decoder(buffer -> new Ping((Integer) ((Buffer) buffer).values.pop()))
                .contextConsumer((ping, context) -> {
                    handled.add(new Object[]{ping, context});
                })
                .added()
                .messageBuilder(Pong.class)
                .codec(new PongCodec())
                .contextConsumer((pong, context) -> {
                    handled.add(new Object[]{pong, context});
                })
                .added();
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
    @DisplayName("the int protocol version is the payload version, and messages are numbered from 0")
    void channelRegisters() {
        assertEquals("3", event.version);
        assertEquals(new PayloadType("testmod:main"), event.registrar.type);
        assertEquals(List.of(0, 1), channel.getRegistrations().stream().map(NetworkShim.PacketRegistration::id).sorted().toList());
    }

    @Test
    @DisplayName("send(message, PLAYER.with(player)) round-trips, and the handler gets the context itself")
    void messageRoundTripsToAContextHandler() {
        channel.send(new Ping(42), NetworkShim.PacketDistributor.PLAYER.with((Object) "steve"));

        Object[] send = Distributor.sent.get(0);
        assertEquals("player", send[0]);
        assertEquals("steve", send[1], "Forge 1.20.2 passes the player itself, not a supplier");
        Buffer buffer = new Buffer();
        event.registrar.codec.encode(buffer, send[2]);
        assertEquals(0, buffer.values.peek(), "the first message added has id 0");
        Object decoded = event.registrar.codec.decode(buffer);

        event.registrar.handler.handle(decoded, context(Flow.SERVERBOUND, "steve"));
        assertEquals(new Ping(42), handled.get(0)[0]);
        NetworkShim.Context context = assertInstanceOf(NetworkShim.Context.class, handled.get(0)[1],
                "a Forge 1.20.2 handler takes the context, not a supplier of it");
        assertEquals("steve", context.getSender());
        assertTrue(context.isServerSide());
        assertFalse(context.isClientSide());
        assertEquals("connection", context.getConnection());
    }

    @Test
    @DisplayName("a message with a StreamCodec encodes and decodes through it, buffer first")
    void streamCodecMessage() {
        channel.send(new Pong("hello"), NetworkShim.PacketDistributor.SERVER.noArg());

        Object[] send = Distributor.sent.get(0);
        assertEquals("server", send[0]);
        Buffer buffer = new Buffer();
        event.registrar.codec.encode(buffer, send[2]);
        Object decoded = event.registrar.codec.decode(buffer);
        event.registrar.handler.handle(decoded, context(Flow.CLIENTBOUND, null));
        assertEquals(new Pong("hello"), handled.get(0)[0]);
        assertTrue(((NetworkShim.Context) handled.get(0)[1]).isClientSide());
    }

    @Test
    @DisplayName("version tests answer as Forge's: exact matches only a present channel of that version")
    void versionTests() {
        NetworkShim.VersionTest exact = NetworkShim.VersionTest.exact(3);
        assertTrue(exact.accepts(NetworkShim.VersionTest.Status.PRESENT, 3));
        assertFalse(exact.accepts(NetworkShim.VersionTest.Status.PRESENT, 2));
        assertTrue(exact.or(NetworkShim.VersionTest.ACCEPT_MISSING).accepts(NetworkShim.VersionTest.Status.MISSING, 0));
        assertFalse(exact.negate().accepts(NetworkShim.VersionTest.Status.PRESENT, 3));
    }

    @Test
    @DisplayName("a Forge 1.21 payload channel registers each payload type with its own codec and sends payloads as they are")
    void payloadChannelPayloads() {
        NetworkShim.resetForTesting();
        Distributor.sent.clear();
        PayloadType helloType = new PayloadType("testmod:hello");
        Codec codec = new Codec() {
            public Object decode(Object buffer) {
                return null;
            }

            public void encode(Object buffer, Object value) {
            }
        };
        List<Object> contexts = new ArrayList<>();
        NetworkShim.PayloadChannel flow = (NetworkShim.PayloadChannel) NetworkShim.ChannelBuilder.named("testmod:payloads")
                .networkProtocolVersion(2).payloadChannel().play().bidirectional();
        NetworkShim.SimpleChannelWrapper payloads = flow.addMain(helloType, codec, (hello, context) -> {
            contexts.add(context);
        }).build();
        Event payloadEvent = new Event();
        NetworkShim.registerPayloads(payloadEvent, new NetworkShim.PayloadBridge(
                getClass().getClassLoader(), Payload.class, PayloadType.class, Codec.class,
                Handler.class, PayloadContext.class, Thread.class, Distributor.class, null));

        assertEquals("2", payloadEvent.version);
        assertSame(helloType, payloadEvent.registrar.type, "the mod's own type, not one per channel");
        assertSame(codec, payloadEvent.registrar.codec, "the mod's own codec");
        assertEquals(Thread.MAIN, payloadEvent.registrar.thread, "addMain runs on the main thread");
        Payload hello = () -> helloType;
        payloads.send(hello, NetworkShim.PacketDistributor.PLAYER.with((Object) "alex"));
        assertSame(hello, Distributor.sent.get(0)[2], "a payload is sent as it is");
        payloadEvent.registrar.handler.handle(hello, context(Flow.SERVERBOUND, "alex"));
        assertEquals("alex", assertInstanceOf(NetworkShim.Context.class, contexts.get(0)).getSender());
    }

    private static PayloadContext context(Flow flow, Object player) {
        return new PayloadContext() {
            public CompletableFuture<Void> enqueueWork(Runnable work) {
                work.run();
                return CompletableFuture.completedFuture(null);
            }

            public Object player() {
                return player;
            }

            public Flow flow() {
                return flow;
            }
        };
    }
}
