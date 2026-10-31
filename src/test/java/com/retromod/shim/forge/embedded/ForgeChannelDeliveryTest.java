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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** On Forge 26.x an old SimpleChannel opens one of Forge's own channels and adds each message to it. */
class ForgeChannelDeliveryTest {

    // ---- a stand-in for Forge 26.x's channel API, with the same member names ----------------

    public static final class Buffer extends ArrayDeque<Integer> {}

    public static final class Builder {
        Object id;
        int version;
        boolean optional;
        static Builder last;

        public static Builder named(Object id) {
            Builder builder = new Builder();
            builder.id = id;
            last = builder;
            return builder;
        }

        public Builder networkProtocolVersion(int version) {
            this.version = version;
            return this;
        }

        public Builder optional() {
            optional = true;
            return this;
        }

        public Channel simpleChannel() {
            return new Channel();
        }
    }

    public static final class Channel {
        final Map<Integer, Message> messages = new HashMap<>();
        final List<Object[]> sent = new ArrayList<>();

        public Message messageBuilder(Class<?> type, int id) {
            return new Message(this, id);
        }

        public void send(Object message, Target target) {
            sent.add(new Object[]{message, target});
        }

        public void reply(Object message, Context context) {
            sent.add(new Object[]{message, "reply"});
        }
    }

    public static final class Message {
        final Channel channel;
        final int id;
        BiConsumer<Object, Object> encoder;
        Function<Object, Object> decoder;
        BiConsumer<Object, Object> handler;

        Message(Channel channel, int id) {
            this.channel = channel;
            this.id = id;
        }

        @SuppressWarnings("unchecked")
        public Message encoder(BiConsumer<?, ?> encoder) {
            this.encoder = (BiConsumer<Object, Object>) encoder;
            return this;
        }

        @SuppressWarnings("unchecked")
        public Message decoder(Function<?, ?> decoder) {
            this.decoder = (Function<Object, Object>) decoder;
            return this;
        }

        @SuppressWarnings("unchecked")
        public Message consumerNetworkThread(BiConsumer<?, ?> handler) {
            this.handler = (BiConsumer<Object, Object>) handler;
            return this;
        }

        public Channel add() {
            channel.messages.put(id, this);
            return channel;
        }
    }

    public record Target(String kind, Object argument) {}

    public static final class Distributor {
        public static final Distributor PLAYER = new Distributor("PLAYER");
        public static final Distributor SERVER = new Distributor("SERVER");
        public static final Distributor ALL = new Distributor("ALL");
        public static final Distributor NEAR = new Distributor("NEAR");
        private final String kind;

        Distributor(String kind) {
            this.kind = kind;
        }

        public Target with(Object argument) {
            return new Target(kind, argument);
        }

        public Target noArg() {
            return new Target(kind, null);
        }
    }

    public record Point(Object excluded, double x, double y, double z, double r2, Object dim) {
        public Point(double x, double y, double z, double r2, Object dim) {
            this(null, x, y, z, r2, dim);
        }
    }

    public static final class Context {
        final boolean serverSide;
        boolean handled;

        Context(boolean serverSide) {
            this.serverSide = serverSide;
        }

        public boolean isServerSide() {
            return serverSide;
        }

        public Object getSender() {
            return "steve";
        }

        public Object getConnection() {
            return "connection";
        }

        public void setPacketHandled(boolean handled) {
            this.handled = handled;
        }

        public CompletableFuture<Void> enqueueWork(Runnable work) {
            work.run();
            return CompletableFuture.completedFuture(null);
        }
    }

    // ---- the mod's side ----------------------------------------------------------------

    record Ping(int value) {}

    private final List<Object[]> handled = new ArrayList<>();
    private NetworkShim.SimpleChannelWrapper channel;

    @BeforeEach
    void openChannel() {
        NetworkShim.resetForTesting();
        NetworkShim.useForgeChannelsForTesting(new NetworkShim.ForgeChannels(
                Builder.class, Distributor.class, Target.class, Point.class));
        channel = (NetworkShim.SimpleChannelWrapper) NetworkShim.newSimpleChannel("testmod:main", () -> "3", null, null);
        channel.registerMessage(7, Ping.class,
                (ping, buffer) -> ((Buffer) buffer).push(ping.value()),
                buffer -> new Ping(((Buffer) buffer).pop()),
                (ping, context) -> {
                    NetworkShim.Context forge = (NetworkShim.Context) ((Supplier<?>) context).get();
                    forge.enqueueWork(() -> handled.add(new Object[]{ping, forge.getSender(), forge.getDirection()}));
                    forge.setPacketHandled(true);
                });
    }

    @AfterEach
    void reset() {
        NetworkShim.resetForTesting();
    }

    @Test
    @DisplayName("The channel opens under its id, optional, with the old version as a number")
    void opensAForgeChannel() {
        assertEquals("testmod:main", Builder.last.id);
        assertEquals(3, Builder.last.version);
        assertTrue(Builder.last.optional, "an old channel tolerated a peer without it");
    }

    @Test
    @DisplayName("A message keeps its id and codec, and its handler gets the old context")
    void messagesRoundTrip() throws Exception {
        Channel forge = (Channel) field(channel, "forgeChannel");
        Message message = forge.messages.get(7);
        assertNotNull(message, "message id 7 was not added to the Forge channel");

        Buffer buffer = new Buffer();
        message.encoder.accept(new Ping(42), buffer);
        Object decoded = message.decoder.apply(buffer);
        assertEquals(new Ping(42), decoded);

        Context context = new Context(true);
        message.handler.accept(decoded, context);
        assertEquals(1, handled.size());
        assertEquals("steve", handled.get(0)[1], "the server side knows its sender");
        assertEquals(NetworkShim.NetworkDirection.PLAY_TO_SERVER, handled.get(0)[2]);
        assertTrue(context.handled, "setPacketHandled reaches Forge's context");
    }

    @Test
    @DisplayName("Old sends reach Forge's distributor targets: the server, a player, and everyone")
    void sendsReachForgeTargets() throws Exception {
        Channel forge = (Channel) field(channel, "forgeChannel");
        channel.sendToServer(new Ping(1));
        channel.send(NetworkShim.PacketDistributor.PLAYER.with(() -> "alex"), new Ping(2));
        channel.send(NetworkShim.PacketDistributor.ALL.noArg(), new Ping(3));

        assertEquals(new Target("SERVER", null), forge.sent.get(0)[1]);
        assertEquals(new Target("PLAYER", "alex"), forge.sent.get(1)[1]);
        assertEquals(new Target("ALL", null), forge.sent.get(2)[1]);
    }

    @Test
    @DisplayName("The old nested channel builder opens the same channel")
    void channelBuilderOpensTheSameChannel() throws Exception {
        NetworkShim.SimpleChannelWrapper built = NetworkShim.ChannelBuilder.named("testmod:other")
                .networkProtocolVersion(() -> "5").simpleChannel();
        assertEquals("testmod:other", Builder.last.id);
        assertEquals(5, Builder.last.version);
        assertNotNull(field(built, "forgeChannel"));
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
