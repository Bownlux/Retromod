/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** What Forge 1.20.1's directions, targets, custom distributors and vanilla packets do, on NetworkShim. */
class ForgeNetworkMembersTest {

    private static final String CLIENTBOUND = "net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket";
    private static final String SERVERBOUND = "net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket";

    // ---- stand-ins for Minecraft's sending side, with the same member names -------------------

    public enum Side { CLIENT, SERVER }

    public static final class Connection {
        final List<Object> sent = new ArrayList<>();
        public void send(Object packet) { sent.add(packet); }
    }

    public static final class Player {
        public final Connection connection = new Connection();
    }

    public static final class PlayerList {
        final List<Object[]> sent = new ArrayList<>();
        public void broadcastAll(Object packet) { sent.add(new Object[]{"all", packet}); }
        public void broadcastAll(Object packet, Object dimension) { sent.add(new Object[]{"dimension", packet, dimension}); }
        public void broadcast(Object excluded, double x, double y, double z, double radius, Object dimension, Object packet) {
            sent.add(new Object[]{"near", packet, radius, dimension});
        }
    }

    public static final class Server {
        final PlayerList players = new PlayerList();
        public PlayerList getPlayerList() { return players; }
    }

    /** 1.21.11's tracking names; {@link OldChunkSource} has 1.21.1's. */
    public static final class ChunkSource {
        final List<Object[]> sent = new ArrayList<>();
        public final ChunkMap chunkMap = new ChunkMap();
        public void sendToTrackingPlayers(Object entity, Object packet) { sent.add(new Object[]{"tracking", packet}); }
        public void sendToTrackingPlayersAndSelf(Object entity, Object packet) { sent.add(new Object[]{"self", packet}); }
    }

    public static final class OldChunkSource {
        final List<Object[]> sent = new ArrayList<>();
        public void broadcast(Object entity, Object packet) { sent.add(new Object[]{"tracking", packet}); }
        public void broadcastAndSend(Object entity, Object packet) { sent.add(new Object[]{"self", packet}); }
    }

    public static final class ChunkMap {
        final Player watcher = new Player();
        public List<Player> getPlayers(Object pos, boolean edgeOnly) { return List.of(watcher); }
    }

    public static final class Level {
        final Object source;
        Level(Object source) { this.source = source; }
        public Object getChunkSource() { return source; }
    }

    public static final class Entity {
        final Level level;
        Entity(Object source) { level = new Level(source); }
        public Level level() { return level; }
    }

    public static final class Chunk {
        final Level level;
        Chunk(Object source) { level = new Level(source); }
        public Level getLevel() { return level; }
        public Object getPos() { return "0,0"; }
    }

    // ---- Forge 26.x stand-ins for building and sending its own packets ------------------------

    public static final class ForgeDirection {
        public static final ForgeDirection PLAY_TO_CLIENT = new ForgeDirection("PLAY_TO_CLIENT");
        public static final ForgeDirection PLAY_TO_SERVER = new ForgeDirection("PLAY_TO_SERVER");
        final String name;
        ForgeDirection(String name) { this.name = name; }
        public Object buildPacket(Object channel, Object message) { return List.of(name, channel, message); }
    }

    public static final class ForgeTarget {
        static final List<Object[]> sent = new ArrayList<>();
        final String kind;
        final Object argument;
        ForgeTarget(String kind, Object argument) { this.kind = kind; this.argument = argument; }
        public void send(Object packet) { sent.add(new Object[]{kind, argument, packet}); }
    }

    public static final class ForgeDistributor {
        public static final ForgeDistributor PLAYER = new ForgeDistributor("PLAYER");
        public static final ForgeDistributor ALL = new ForgeDistributor("ALL");
        final String kind;
        ForgeDistributor(String kind) { this.kind = kind; }
        public ForgeTarget with(Object argument) { return new ForgeTarget(kind, argument); }
        public ForgeTarget noArg() { return new ForgeTarget(kind, null); }
    }

    record Ping(int value) {}

    @BeforeEach
    @AfterEach
    void reset() {
        NetworkShim.resetForTesting();
        ForgeTarget.sent.clear();
    }

    @Test
    @DisplayName("A direction's reception and origination sides are Forge's, found on the loader's LogicalSide")
    void directionSides() {
        assertEquals("SERVER", NetworkShim.NetworkDirection.PLAY_TO_SERVER.receptionSideName());
        assertEquals("CLIENT", NetworkShim.NetworkDirection.PLAY_TO_SERVER.originationSideName());
        assertEquals("CLIENT", NetworkShim.NetworkDirection.PLAY_TO_CLIENT.receptionSideName());
        assertEquals("SERVER", NetworkShim.NetworkDirection.PLAY_TO_CLIENT.originationSideName());
        assertEquals("SERVER", NetworkShim.NetworkDirection.LOGIN_TO_SERVER.receptionSideName());
        assertEquals("CLIENT", NetworkShim.NetworkDirection.LOGIN_TO_CLIENT.receptionSideName());
        // The first name the loader lacks is skipped, as NeoForge's is on Forge.
        assertEquals(Side.SERVER, NetworkShim.logicalSide("SERVER", getClass().getClassLoader(),
                "net.neoforged.fml.LogicalSide", Side.class.getName()));
        assertThrows(IllegalStateException.class, () -> NetworkShim.NetworkDirection.PLAY_TO_SERVER.getReceptionSide(),
                "with no loader there is no side to answer");
    }

    @Test
    @DisplayName("ACCEPTVANILLA is Forge's vanilla-peer version string")
    void acceptVanilla() {
        assertTrue(NetworkShim.ACCEPTVANILLA.startsWith("ALLOWVANILLA"), NetworkShim.ACCEPTVANILLA);
    }

    @Test
    @DisplayName("A mod's own distributor builds its consumer from the bound argument and keeps its direction")
    void customDistributor() {
        List<Object> packets = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        BiFunction<Object, Supplier<?>, Consumer<Object>> functor = (distributor, argument) -> {
            arguments.add(argument.get());
            return packets::add;
        };
        NetworkShim.PacketDistributor allExcept = new NetworkShim.PacketDistributor(functor,
                NetworkShim.NetworkDirection.PLAY_TO_CLIENT);

        NetworkShim.PacketDistributor.PacketTarget target = allExcept.with(() -> "steve");
        assertEquals(NetworkShim.NetworkDirection.PLAY_TO_CLIENT, target.getDirection());
        target.send("packet");
        assertEquals(List.of("steve"), arguments);
        assertEquals(List.of("packet"), packets);
        // The built-in kinds keep Forge's directions.
        assertEquals(NetworkShim.NetworkDirection.PLAY_TO_SERVER, NetworkShim.PacketDistributor.SERVER.noArg().getDirection());
        assertEquals(NetworkShim.NetworkDirection.PLAY_TO_CLIENT, NetworkShim.PacketDistributor.ALL.noArg().getDirection());
    }

    @Test
    @DisplayName("A built packet reaches a player, everyone, a dimension, nearby players, and connections as Minecraft sends it")
    void vanillaSendsByPlayerList() {
        Server server = new Server();
        NetworkShim.VanillaTargets.serverForTesting = server;
        Player player = new Player();
        NetworkShim.PacketDistributor.PLAYER.with(() -> player).send("p1");
        NetworkShim.PacketDistributor.ALL.noArg().send("p2");
        NetworkShim.PacketDistributor.DIMENSION.with(() -> "nether").send("p3");
        NetworkShim.PacketDistributor.NEAR.with(NetworkShim.PacketDistributor.TargetPoint.p(1, 2, 3, 64, "overworld")).send("p4");
        Connection connection = new Connection();
        NetworkShim.PacketDistributor.NMLIST.with(() -> List.of(connection)).send("p5");

        assertEquals(List.of("p1"), player.connection.sent);
        assertArrayEquals(new Object[]{"all", "p2"}, server.players.sent.get(0));
        assertArrayEquals(new Object[]{"dimension", "p3", "nether"}, server.players.sent.get(1));
        assertArrayEquals(new Object[]{"near", "p4", 8.0, "overworld"}, server.players.sent.get(2),
                "Forge's radius squared becomes the player list's radius");
        assertEquals(List.of("p5"), connection.sent);
    }

    @Test
    @DisplayName("Tracking sends use 1.21.11's names, and 1.21.1's where only those exist")
    void trackingSends() {
        ChunkSource current = new ChunkSource();
        NetworkShim.PacketDistributor.TRACKING_ENTITY.with(() -> new Entity(current)).send("p1");
        NetworkShim.PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> new Entity(current)).send("p2");
        NetworkShim.PacketDistributor.TRACKING_CHUNK.with(() -> new Chunk(current)).send("p3");
        assertArrayEquals(new Object[]{"tracking", "p1"}, current.sent.get(0));
        assertArrayEquals(new Object[]{"self", "p2"}, current.sent.get(1));
        assertEquals(List.of("p3"), current.chunkMap.watcher.connection.sent);

        OldChunkSource old = new OldChunkSource();
        NetworkShim.PacketDistributor.TRACKING_ENTITY.with(() -> new Entity(old)).send("p4");
        NetworkShim.PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> new Entity(old)).send("p5");
        assertArrayEquals(new Object[]{"tracking", "p4"}, old.sent.get(0));
        assertArrayEquals(new Object[]{"self", "p5"}, old.sent.get(1));
    }

    @Test
    @DisplayName("On NeoForge toVanillaPacket wraps the message's payload in the packet for its direction")
    void neoForgeVanillaPacket() throws Exception {
        NetworkShim.SimpleChannelWrapper channel = neoForgeChannel();
        Object clientbound = channel.toVanillaPacket(new Ping(1), NetworkShim.NetworkDirection.PLAY_TO_CLIENT);
        Object serverbound = channel.toVanillaPacket(new Ping(2), NetworkShim.NetworkDirection.PLAY_TO_SERVER);
        assertEquals(CLIENTBOUND, clientbound.getClass().getName());
        assertEquals(SERVERBOUND, serverbound.getClass().getName());
        Object payload = clientbound.getClass().getField("payload").get(clientbound);
        assertEquals(new ForgePayloadDeliveryTest.PayloadType("testmod:main"),
                ((ForgePayloadDeliveryTest.Payload) payload).type(), "the packet carries the channel's payload");
    }

    @Test
    @DisplayName("A message sent to a mod's own distributor reaches its consumer as the built packet")
    void messageToACustomDistributor() throws Exception {
        NetworkShim.SimpleChannelWrapper channel = neoForgeChannel();
        List<Object> packets = new ArrayList<>();
        BiFunction<Object, Supplier<?>, Consumer<Object>> functor = (distributor, argument) -> packets::add;
        NetworkShim.PacketDistributor everyone = new NetworkShim.PacketDistributor(functor,
                NetworkShim.NetworkDirection.PLAY_TO_CLIENT);
        channel.send(everyone.noArg(), new Ping(3));
        assertEquals(1, packets.size());
        assertEquals(CLIENTBOUND, packets.get(0).getClass().getName());
    }

    @Test
    @DisplayName("On Forge 26.x a packet is built by Forge's direction and sent through Forge's own target")
    void forgePacketsAndTargets() {
        NetworkShim.useForgeChannelsForTesting(new NetworkShim.ForgeChannels(ForgeChannelDeliveryTest.Builder.class,
                ForgeDistributor.class, ForgeTarget.class, ForgeChannelDeliveryTest.Point.class, ForgeDirection.class));
        NetworkShim.SimpleChannelWrapper channel = (NetworkShim.SimpleChannelWrapper) NetworkShim.newSimpleChannel(
                "testmod:main", () -> "3", null, null);
        Object packet = channel.toVanillaPacket(new Ping(4), NetworkShim.NetworkDirection.PLAY_TO_CLIENT);
        assertEquals("PLAY_TO_CLIENT", ((List<?>) packet).get(0));
        assertEquals(new Ping(4), ((List<?>) packet).get(2));

        NetworkShim.PacketDistributor.PLAYER.with(() -> "alex").send(packet);
        assertEquals("PLAYER", ForgeTarget.sent.get(0)[0]);
        assertEquals("alex", ForgeTarget.sent.get(0)[1]);
        assertSame(packet, ForgeTarget.sent.get(0)[2]);
    }

    @Test
    @DisplayName("The login members keep the builder chain, and the message still registers")
    void loginMembers() {
        NetworkShim.SimpleChannelWrapper channel = (NetworkShim.SimpleChannelWrapper) NetworkShim.newSimpleChannel(
                "testmod:login", () -> "1", null, null);
        NetworkShim.MessageBuilder<Ping> builder = channel.messageBuilder(Ping.class, 0);
        assertSame(builder, builder.loginIndex(ping -> 0, (ping, index) -> {}).buildLoginPacketList(local -> List.of())
                .markAsLoginPacket().noResponse());
        builder.encoder((ping, buffer) -> {}).decoder(buffer -> new Ping(0)).add();
        assertEquals(1, channel.getRegistrations().size());
    }

    /** A channel registered with NeoForge stand-ins, whose loader also has Minecraft's two payload packets. */
    private NetworkShim.SimpleChannelWrapper neoForgeChannel() {
        NetworkShim.SimpleChannelWrapper channel = (NetworkShim.SimpleChannelWrapper) NetworkShim.newSimpleChannel(
                "testmod:main", () -> "3", null, null);
        channel.registerMessage(7, Ping.class, (ping, buffer) -> {}, buffer -> new Ping(0), (ping, context) -> {});
        NetworkShim.registerPayloads(new ForgePayloadDeliveryTest.Event(), new NetworkShim.PayloadBridge(
                new PacketLoader(getClass().getClassLoader()), ForgePayloadDeliveryTest.Payload.class,
                ForgePayloadDeliveryTest.PayloadType.class, ForgePayloadDeliveryTest.Codec.class,
                ForgePayloadDeliveryTest.Handler.class, ForgePayloadDeliveryTest.PayloadContext.class,
                ForgePayloadDeliveryTest.Thread.class, ForgePayloadDeliveryTest.Distributor.class, null));
        return channel;
    }

    /** Defines the two packets as classes holding the payload they were built with. */
    private static final class PacketLoader extends ClassLoader {
        PacketLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals(CLIENTBOUND) && !name.equals(SERVERBOUND)) throw new ClassNotFoundException(name);
            String internal = name.replace('.', '/');
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_SUPER, internal, null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC | ACC_FINAL, "payload", "Ljava/lang/Object;", null, null).visitEnd();
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(ALOAD, 1);
            init.visitFieldInsn(PUTFIELD, internal, "payload", "Ljava/lang/Object;");
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            cw.visitEnd();
            byte[] bytes = cw.toByteArray();
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
