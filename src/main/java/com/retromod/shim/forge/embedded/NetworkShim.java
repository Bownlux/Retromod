/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * Bridges Forge's SimpleChannel networking to NeoForge's codec-based PayloadRegistrar.
 */
package com.retromod.shim.forge.embedded;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;

/**
 * Stand-in for Forge's {@code NetworkRegistry} and {@code SimpleChannel} on NeoForge.
 *
 * <p>Each Forge channel becomes one NeoForge payload type with the channel's id. A packet carries
 * the Forge message id as a VarInt, then whatever the mod's own encoder writes, so the mod's
 * encoder, decoder, and handler run unchanged. Registration happens when NeoForge locks its network
 * registry, after every mod has loaded, so channels a mod creates during construction, setup, or
 * IMC are all included.
 *
 * <p>Retromod is compiled without Minecraft or NeoForge, so every host type is reached through
 * reflection, and the payload, codec, and handler are interface proxies.
 */
public final class NetworkShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final Map<String, SimpleChannelWrapper> CHANNELS = new ConcurrentHashMap<>();
    private static volatile PayloadBridge bridge;
    // Forge 26.x still has its own SimpleChannel, so there a channel registers each message on it.
    private static volatile ForgeChannels forgeChannels;
    private static volatile boolean forgeChecked;

    private NetworkShim() {}

    /** Mirrors NetworkRegistry.newSimpleChannel. */
    public static Object newSimpleChannel(Object channelId,
            Supplier<String> networkProtocolVersion,
            Predicate<String> clientAcceptedVersions,
            Predicate<String> serverAcceptedVersions) {
        String version = networkProtocolVersion == null ? "1" : networkProtocolVersion.get();
        SimpleChannelWrapper channel = CHANNELS.computeIfAbsent(String.valueOf(channelId),
                key -> new SimpleChannelWrapper(channelId, version));
        ForgeChannels forge = forgeChannels();
        if (forge != null) {
            channel.openOnForge(forge);
        } else if (bridge != null) {
            LOGGER.warn("Forge network channel {} was created after NeoForge registered its "
                    + "network payloads, so its messages cannot be sent.", channelId);
        }
        return channel;
    }

    /** Forge's version string for a peer that does not have the channel. */
    private static final String ABSENT = "ABSENT \ud83e\udd14";

    /** Mirrors NetworkRegistry.acceptMissingOr(version): the exact version, or a peer without the channel. */
    public static Predicate<String> acceptMissingOr(String version) {
        return acceptMissingOr(version::equals);
    }

    /** Mirrors NetworkRegistry.acceptMissingOr(test). */
    public static Predicate<String> acceptMissingOr(Predicate<String> test) {
        return remote -> ABSENT.equals(remote) || test.test(remote);
    }

    /** Mirrors Forge 1.20.1's {@code NetworkRegistry.ChannelBuilder}, which opens the same channel as newSimpleChannel. */
    public static final class ChannelBuilder {
        private final Object channelId;
        private Supplier<String> version = () -> "1";
        private Predicate<String> clientAccepted = remote -> true;
        private Predicate<String> serverAccepted = remote -> true;

        private ChannelBuilder(Object channelId) {
            this.channelId = channelId;
        }

        public static ChannelBuilder named(Object channelId) {
            return new ChannelBuilder(channelId);
        }

        public ChannelBuilder networkProtocolVersion(Supplier<String> version) {
            this.version = version;
            return this;
        }

        public ChannelBuilder clientAcceptedVersions(Predicate<String> accepted) {
            this.clientAccepted = accepted;
            return this;
        }

        public ChannelBuilder serverAcceptedVersions(Predicate<String> accepted) {
            this.serverAccepted = accepted;
            return this;
        }

        public SimpleChannelWrapper simpleChannel() {
            return (SimpleChannelWrapper) newSimpleChannel(channelId, version, clientAccepted, serverAccepted);
        }
    }

    /**
     * Subscribes to NeoForge's payload registration on the active mod's event bus. Called from
     * Retromod's own NeoForge constructor; does nothing on a host without the payload API.
     */
    public static void listenForPayloadRegistration() {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> event = Class.forName(
                    "net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent", false, loader);
            Class<?> loadingContext = Class.forName("net.neoforged.fml.ModLoadingContext", false, loader);
            Object context = loadingContext.getMethod("get").invoke(null);
            Object container = loadingContext.getMethod("getActiveContainer").invoke(context);
            Object bus = container.getClass().getMethod("getEventBus").invoke(container);
            Method addListener = Class.forName("net.neoforged.bus.api.IEventBus", false, loader)
                    .getMethod("addListener", Class.class, Consumer.class);
            Consumer<Object> listener = NetworkShim::registerPayloads;
            addListener.invoke(bus, event, listener);

            // From 1.21.11 NeoForge also wants a client-side handler for every payload the server
            // can send, registered through its own client event, and refuses to start without it.
            try {
                Class<?> clientEvent = Class.forName(
                        "net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent",
                        false, loader);
                Consumer<Object> clientListener = NetworkShim::registerClientHandlers;
                addListener.invoke(bus, clientEvent, clientListener);
            } catch (ClassNotFoundException olderHost) {
                // 1.21.1 registers both sides through playBidirectional.
            }
        } catch (ClassNotFoundException e) {
            LOGGER.debug("No NeoForge payload registration on this host; Forge channels stay inert");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Retromod could not listen for NeoForge payload registration, so "
                    + "transformed Forge mods cannot send network messages: {}", e.toString());
        }
    }

    /** {@code RegisterPayloadHandlersEvent} listener: registers every collected Forge channel. */
    public static void registerPayloads(Object event) {
        try {
            registerPayloads(event, PayloadBridge.resolve(event.getClass().getClassLoader()));
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Retromod could not reach NeoForge's payload API, so transformed Forge "
                    + "mods cannot send network messages: {}", e.toString());
        }
    }

    static void registerPayloads(Object event, PayloadBridge payloads) {
        bridge = payloads;
        for (SimpleChannelWrapper channel : CHANNELS.values()) {
            try {
                payloads.register(event, channel);
                LOGGER.info("Registered Forge network channel {} with {} message type(s)",
                        channel.channelId, channel.registrations.size());
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("Retromod could not register Forge network channel {}: {}",
                        channel.channelId, e.toString());
            }
        }
    }

    /** {@code RegisterClientPayloadHandlersEvent} listener: the client side of every channel. */
    public static void registerClientHandlers(Object event) {
        for (SimpleChannelWrapper channel : CHANNELS.values()) {
            if (channel.payloadType() == null || channel.payloadHandler == null) continue;
            try {
                PayloadBridge.method(event.getClass(), "register", 2, false)
                        .invoke(event, channel.payloadType(), channel.payloadHandler);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("Retromod could not register the client side of Forge network channel {}: {}",
                        channel.channelId, e.toString());
            }
        }
    }

    static void resetForTesting() {
        CHANNELS.clear();
        bridge = null;
        forgeChannels = null;
        forgeChecked = false;
    }

    private static PayloadBridge requireBridge() {
        PayloadBridge current = bridge;
        if (current == null) {
            throw new IllegalStateException("Forge network messages cannot be sent before "
                    + "NeoForge registers its network payloads.");
        }
        return current;
    }

    /** Forge 26.x's channel API, or null on NeoForge and on hosts without it. */
    static ForgeChannels forgeChannels() {
        if (!forgeChecked) {
            forgeChannels = ForgeChannels.resolve(NetworkShim.class.getClassLoader());
            forgeChecked = true;
        }
        return forgeChannels;
    }

    static void useForgeChannelsForTesting(ForgeChannels channels) {
        forgeChannels = channels;
        forgeChecked = true;
    }

    /** Mirrors Forge's SimpleChannel. */
    public static class SimpleChannelWrapper {
        private final Object channelId;
        private final String protocolVersion;
        private final Map<Integer, PacketRegistration<?>> registrations = new ConcurrentHashMap<>();
        private final Map<Class<?>, PacketRegistration<?>> byType = new ConcurrentHashMap<>();
        private volatile Object payloadType;
        private volatile Object payloadHandler;
        private volatile ForgeChannels forge;
        private volatile Object forgeChannel;

        public SimpleChannelWrapper(Object channelId, String protocolVersion) {
            this.channelId = channelId;
            this.protocolVersion = protocolVersion;
        }

        /** Mirrors SimpleChannel.registerMessage. */
        public <T> Object registerMessage(int id, Class<T> messageType,
                BiConsumer<T, Object> encoder,
                Function<Object, T> decoder,
                BiConsumer<T, Object> messageConsumer) {
            PacketRegistration<T> registration =
                    new PacketRegistration<>(id, messageType, encoder, decoder, messageConsumer);
            registrations.put(id, registration);
            byType.put(messageType, registration);
            if (forge != null) forge.add(forgeChannel, registration);
            return null;
        }

        /** Opens this channel on Forge 26.x's own channel API. */
        void openOnForge(ForgeChannels channels) {
            if (forge != null) return;
            forgeChannel = channels.open(channelId, protocolVersion);
            forge = channels;
        }

        /** The Optional&lt;NetworkDirection&gt; overload; NeoForge registers both ways. */
        public <T> Object registerMessage(int id, Class<T> messageType,
                BiConsumer<T, Object> encoder,
                Function<Object, T> decoder,
                BiConsumer<T, Object> messageConsumer,
                Optional<?> direction) {
            return registerMessage(id, messageType, encoder, decoder, messageConsumer);
        }

        public <T> MessageBuilder<T> messageBuilder(Class<T> type, int id) {
            return new MessageBuilder<>(this, type, id);
        }

        /** The direction-qualified overload (MCreator's addNetworkMessage). */
        public <T> MessageBuilder<T> messageBuilder(Class<T> type, int id, Object direction) {
            return new MessageBuilder<>(this, type, id);
        }

        /** The same overload once {@code NetworkDirection} has moved to the stand-in below. */
        public <T> MessageBuilder<T> messageBuilder(Class<T> type, int id, NetworkDirection direction) {
            return new MessageBuilder<>(this, type, id);
        }

        public void sendToServer(Object message) {
            if (forge != null) {
                forge.sendToServer(forgeChannel, message);
                return;
            }
            requireBridge().sendToServer(payload(message));
        }

        /** {@code send(PacketDistributor.PLAYER.with(() -> player), message)} and the other targets. */
        public void send(Object target, Object message) {
            if (!(target instanceof PacketDistributor.PacketTarget packetTarget)) {
                throw new IllegalArgumentException("Unsupported Forge packet target: " + target);
            }
            if (forge != null) {
                forge.send(forgeChannel, packetTarget, message);
                return;
            }
            requireBridge().send(packetTarget, payload(message));
        }

        /** {@code sendTo(message, connection, direction)}: writes the packet to one connection. */
        public void sendTo(Object message, Object connection, NetworkDirection direction) {
            if (forge != null) {
                forge.sendToConnection(forgeChannel, message, connection);
                return;
            }
            requireBridge().sendToConnection(connection, payload(message),
                    direction == NetworkDirection.PLAY_TO_SERVER
                            || direction == NetworkDirection.LOGIN_TO_SERVER);
        }

        /** {@code reply(message, context)}: answers on the connection the request came from. */
        public void reply(Object message, Context context) {
            if (forge != null) {
                forge.reply(forgeChannel, message, context.payloadContext);
                return;
            }
            requireBridge().reply(context.payloadContext, payload(message));
        }

        /** NeoForge negotiates channels itself, so a remote that connected has this one. */
        public boolean isRemotePresent(Object connection) {
            return true;
        }

        public Object getResourceLocation() {
            return channelId;
        }

        public List<PacketRegistration<?>> getRegistrations() {
            return new ArrayList<>(registrations.values());
        }

        PacketRegistration<?> registration(int id) {
            PacketRegistration<?> registration = registrations.get(id);
            if (registration == null) {
                throw new IllegalStateException("Forge network channel " + channelId
                        + " received unknown message id " + id);
            }
            return registration;
        }

        private Object payload(Object message) {
            PacketRegistration<?> registration = registrationFor(message.getClass());
            return requireBridge().payload(this, registration, message);
        }

        private PacketRegistration<?> registrationFor(Class<?> messageType) {
            for (Class<?> type = messageType; type != null; type = type.getSuperclass()) {
                PacketRegistration<?> registration = byType.get(type);
                if (registration != null) return registration;
            }
            throw new IllegalArgumentException("Forge network channel " + channelId
                    + " has no message registered for " + messageType.getName());
        }

        Object payloadType() {
            return payloadType;
        }

        void bindPayloadType(Object type) {
            payloadType = type;
        }

        String protocolVersion() {
            return protocolVersion;
        }

        Object channelId() {
            return channelId;
        }
    }

    /**
     * Stand-in for Forge's {@code NetworkDirection}, which NeoForge deleted. Mods read its constants
     * to pass to {@code registerMessage} and {@code messageBuilder}, so the class has to exist even
     * though NeoForge registers both ways.
     */
    public enum NetworkDirection {
        PLAY_TO_SERVER,
        PLAY_TO_CLIENT,
        LOGIN_TO_SERVER,
        LOGIN_TO_CLIENT
    }

    /**
     * Stand-in for Forge's {@code NetworkEvent.Context}, backed by NeoForge's payload context.
     * {@code getSender} and {@code getNetworkManager} return Object here because Retromod cannot
     * name Minecraft types; the shim casts the result back at the mod's call site.
     */
    public static final class Context {
        private final Object payloadContext;
        private final ForgeChannels forge;

        Context(Object payloadContext) {
            this(payloadContext, null);
        }

        Context(Object payloadContext, ForgeChannels forge) {
            this.payloadContext = payloadContext;
            this.forge = forge;
        }

        public CompletableFuture<Void> enqueueWork(Runnable work) {
            if (forge != null) return forge.enqueueWork(payloadContext, work);
            return requireBridge().enqueueWork(payloadContext, work);
        }

        /** The sending player on the server, and null on the client, as in Forge. */
        public Object getSender() {
            if (getDirection() != NetworkDirection.PLAY_TO_SERVER) return null;
            return forge != null ? forge.contextCall(payloadContext, "getSender")
                    : requireBridge().player(payloadContext);
        }

        public Object getNetworkManager() {
            return forge != null ? forge.contextCall(payloadContext, "getConnection")
                    : requireBridge().connection(payloadContext);
        }

        public NetworkDirection getDirection() {
            boolean serverbound = forge != null
                    ? Boolean.TRUE.equals(forge.contextCall(payloadContext, "isServerSide"))
                    : requireBridge().isServerbound(payloadContext);
            return serverbound ? NetworkDirection.PLAY_TO_SERVER : NetworkDirection.PLAY_TO_CLIENT;
        }

        /** Forge keeps the handled flag; NeoForge treats a handler that returns as handled. */
        public void setPacketHandled(boolean handled) {
            if (forge != null) forge.contextCall(payloadContext, "setPacketHandled", handled);
        }

        public boolean getPacketHandled() {
            return forge == null || Boolean.TRUE.equals(forge.contextCall(payloadContext, "getPacketHandled"));
        }
    }

    /** Fluent packet registration builder. */
    /** Forge's {@code SimpleChannel.MessageBuilder.ToBooleanBiFunction}. */
    public interface ToBooleanBiFunction<T, U> {
        boolean applyAsBool(T first, U second);
    }

    public static class MessageBuilder<T> {
        private final SimpleChannelWrapper channel;
        private final Class<T> type;
        private final int id;
        private BiConsumer<T, Object> encoder;
        private Function<Object, T> decoder;
        private BiConsumer<T, Object> consumer;

        public MessageBuilder(SimpleChannelWrapper channel, Class<T> type, int id) {
            this.channel = channel;
            this.type = type;
            this.id = id;
        }

        public MessageBuilder<T> encoder(BiConsumer<T, Object> encoder) {
            this.encoder = encoder;
            return this;
        }

        public MessageBuilder<T> decoder(Function<Object, T> decoder) {
            this.decoder = decoder;
            return this;
        }

        public MessageBuilder<T> consumer(BiConsumer<T, Object> consumer) {
            this.consumer = consumer;
            return this;
        }

        /** Forge's main-thread variant (the one MCreator's scaffold uses). */
        public MessageBuilder<T> consumerMainThread(BiConsumer<T, Object> consumer) {
            return consumer(consumer);
        }

        /** Forge's network-thread variant. NeoForge runs every bridged handler on the main thread. */
        public MessageBuilder<T> consumerNetworkThread(BiConsumer<T, Object> consumer) {
            return consumer(consumer);
        }

        /**
         * Forge's handler that reports whether it handled the packet. NeoForge marks every bridged
         * packet handled, so the answer is not needed.
         */
        public MessageBuilder<T> consumer(ToBooleanBiFunction<T, Object> handler) {
            return consumer((BiConsumer<T, Object>) handler::applyAsBool);
        }

        /** The network-thread spelling of {@link #consumer(ToBooleanBiFunction)}. */
        public MessageBuilder<T> consumerNetworkThread(ToBooleanBiFunction<T, Object> handler) {
            return consumer(handler);
        }

        public void add() {
            channel.registerMessage(id, type, encoder, decoder, consumer);
        }
    }

    public record PacketRegistration<T>(
        int id,
        Class<T> messageType,
        BiConsumer<T, Object> encoder,
        Function<Object, T> decoder,
        BiConsumer<T, Object> consumer
    ) {
        @SuppressWarnings("unchecked")
        void encode(Object message, Object buffer) {
            ((BiConsumer<Object, Object>) (BiConsumer<?, Object>) encoder).accept(message, buffer);
        }

        @SuppressWarnings("unchecked")
        void handle(Object message, Context context) {
            if (consumer == null) return;
            Supplier<Context> supplier = () -> context;
            ((BiConsumer<Object, Object>) (BiConsumer<?, Object>) consumer).accept(message, supplier);
        }
    }

    /**
     * Stand-in for Forge's {@code PacketDistributor}. Each constant names a kind of target, and
     * {@code with} or {@code noArg} binds the argument the mod supplies.
     */
    public static final class PacketDistributor {
        public static final PacketDistributor PLAYER = new PacketDistributor("PLAYER");
        public static final PacketDistributor DIMENSION = new PacketDistributor("DIMENSION");
        public static final PacketDistributor NEAR = new PacketDistributor("NEAR");
        public static final PacketDistributor ALL = new PacketDistributor("ALL");
        public static final PacketDistributor SERVER = new PacketDistributor("SERVER");
        public static final PacketDistributor TRACKING_ENTITY = new PacketDistributor("TRACKING_ENTITY");
        public static final PacketDistributor TRACKING_ENTITY_AND_SELF =
                new PacketDistributor("TRACKING_ENTITY_AND_SELF");
        public static final PacketDistributor TRACKING_CHUNK = new PacketDistributor("TRACKING_CHUNK");
        public static final PacketDistributor NMLIST = new PacketDistributor("NMLIST");

        private final String kind;

        private PacketDistributor(String kind) {
            this.kind = kind;
        }

        public PacketTarget with(Supplier<?> argument) {
            return new PacketTarget(kind, argument.get());
        }

        public PacketTarget noArg() {
            return new PacketTarget(kind, null);
        }

        /** A bound target: the kind of send and the player, entity, chunk, or point it names. */
        public record PacketTarget(String kind, Object argument) {}

        /**
         * Forge's {@code TargetPoint}. Forge passes the radius squared; NeoForge takes the radius.
         * Built through {@link #of} because its constructors name Minecraft types.
         */
        public record TargetPoint(Object excluded, double x, double y, double z, double r2,
                Object dimension) {
            public static TargetPoint of(Object excluded, double x, double y, double z, double r2,
                    Object dimension) {
                return new TargetPoint(excluded, x, y, z, r2, dimension);
            }

            public static TargetPoint of(double x, double y, double z, double r2, Object dimension) {
                return new TargetPoint(null, x, y, z, r2, dimension);
            }

            public static Supplier<TargetPoint> p(double x, double y, double z, double r2,
                    Object dimension) {
                TargetPoint point = of(x, y, z, r2, dimension);
                return () -> point;
            }
        }
    }

    /** The host types and calls a Forge channel needs, resolved once from NeoForge's loader. */
    static final class PayloadBridge {
        private final ClassLoader loader;
        private final Class<?> payloadInterface;
        private final Class<?> payloadType;
        private final Class<?> streamCodec;
        private final Class<?> payloadHandler;
        private final Class<?> handlerThread;
        private final Class<?> payloadContext;
        private final Class<?> distributor;
        private final Class<?> clientDistributor;

        PayloadBridge(ClassLoader loader, Class<?> payloadInterface, Class<?> payloadType,
                Class<?> streamCodec, Class<?> payloadHandler, Class<?> payloadContext,
                Class<?> handlerThread, Class<?> distributor, Class<?> clientDistributor) {
            this.loader = loader;
            this.payloadInterface = payloadInterface;
            this.payloadType = payloadType;
            this.streamCodec = streamCodec;
            this.payloadHandler = payloadHandler;
            this.payloadContext = payloadContext;
            this.handlerThread = handlerThread;
            this.distributor = distributor;
            this.clientDistributor = clientDistributor;
        }

        static PayloadBridge resolve(ClassLoader loader) throws ClassNotFoundException {
            Class<?> payload = Class.forName(
                    "net.minecraft.network.protocol.common.custom.CustomPacketPayload", false, loader);
            return new PayloadBridge(loader, payload,
                    Class.forName(payload.getName() + "$Type", false, loader),
                    Class.forName("net.minecraft.network.codec.StreamCodec", false, loader),
                    Class.forName("net.neoforged.neoforge.network.handling.IPayloadHandler", false, loader),
                    Class.forName("net.neoforged.neoforge.network.handling.IPayloadContext", false, loader),
                    Class.forName("net.neoforged.neoforge.network.registration.HandlerThread", false, loader),
                    Class.forName("net.neoforged.neoforge.network.PacketDistributor", false, loader),
                    optionalClass("net.neoforged.neoforge.client.network.ClientPacketDistributor", loader));
        }

        private static Class<?> optionalClass(String name, ClassLoader loader) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }

        /** Registers one Forge channel as a bidirectional, optional, main-thread payload. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        void register(Object event, SimpleChannelWrapper channel) throws ReflectiveOperationException {
            Object type = payloadType.getConstructors()[0].newInstance(channel.channelId());
            channel.bindPayloadType(type);

            Object registrar = event.getClass().getMethod("registrar", String.class)
                    .invoke(event, channel.protocolVersion());
            registrar = registrar.getClass().getMethod("optional").invoke(registrar);
            registrar = registrar.getClass().getMethod("executesOn", handlerThread)
                    .invoke(registrar, Enum.valueOf((Class) handlerThread, "MAIN"));

            Object codec = proxy(streamCodec, new CodecHandler(this, channel));
            Object handler = proxy(payloadHandler, new HandlerHandler(this));
            channel.payloadHandler = handler;
            method(registrar.getClass(), "playBidirectional", 3, false)
                    .invoke(registrar, type, codec, handler);
        }

        Object payload(SimpleChannelWrapper channel, PacketRegistration<?> registration, Object message) {
            if (channel.payloadType() == null) {
                throw new IllegalStateException("Forge network channel " + channel.channelId()
                        + " was not registered with NeoForge, so it cannot send messages.");
            }
            return proxy(payloadInterface, new Payload(channel.payloadType(), registration, message));
        }

        void sendToServer(Object payload) {
            Class<?> owner = clientDistributor != null ? clientDistributor : distributor;
            invokeStatic(owner, "sendToServer", payload);
        }

        void send(PacketDistributor.PacketTarget target, Object payload) {
            Object argument = target.argument();
            switch (target.kind()) {
                case "PLAYER" -> invokeStatic(distributor, "sendToPlayer", argument, payload);
                case "ALL" -> invokeStatic(distributor, "sendToAllPlayers", payload);
                case "SERVER" -> sendToServer(payload);
                case "TRACKING_ENTITY" ->
                        invokeStatic(distributor, "sendToPlayersTrackingEntity", argument, payload);
                case "TRACKING_ENTITY_AND_SELF" ->
                        invokeStatic(distributor, "sendToPlayersTrackingEntityAndSelf", argument, payload);
                case "TRACKING_CHUNK" -> invokeStatic(distributor, "sendToPlayersTrackingChunk",
                        call(argument, "getLevel"), call(argument, "getPos"), payload);
                case "DIMENSION" -> invokeStatic(distributor, "sendToPlayersInDimension",
                        serverLevel(argument), payload);
                case "NEAR" -> {
                    PacketDistributor.TargetPoint point = (PacketDistributor.TargetPoint) argument;
                    invokeStatic(distributor, "sendToPlayersNear", serverLevel(point.dimension()),
                            point.excluded(), point.x(), point.y(), point.z(),
                            Math.sqrt(point.r2()), payload);
                }
                case "NMLIST" -> {
                    for (Object connection : (Iterable<?>) argument) {
                        sendToConnection(connection, payload, false);
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported Forge packet target "
                        + target.kind());
            }
        }

        void sendToConnection(Object connection, Object payload, boolean serverbound) {
            String packet = serverbound
                    ? "net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket"
                    : "net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket";
            try {
                Object wrapped = Class.forName(packet, false, loader).getConstructors()[0]
                        .newInstance(payload);
                method(connection.getClass(), "send", 1, false).invoke(connection, wrapped);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not send a Forge network message", e);
            }
        }

        void reply(Object context, Object payload) {
            contextCall(context, "reply", payload);
        }

        @SuppressWarnings("unchecked")
        CompletableFuture<Void> enqueueWork(Object context, Runnable work) {
            try {
                return (CompletableFuture<Void>) payloadContext.getMethod("enqueueWork", Runnable.class)
                        .invoke(context, work);
            } catch (InvocationTargetException e) {
                // The mod's own work failed; report its exception, not the reflective wrapper.
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not queue Forge network work", e);
            }
        }

        Object player(Object context) {
            return contextCall(context, "player");
        }

        Object connection(Object context) {
            return contextCall(context, "connection");
        }

        boolean isServerbound(Object context) {
            Object flow = contextCall(context, "flow");
            return flow instanceof Enum<?> direction && direction.name().equals("SERVERBOUND");
        }

        /** NeoForge's context implementations are not public, so calls go through the interface. */
        private Object contextCall(Object context, String name, Object... args) {
            try {
                return method(payloadContext, name, args.length, false).invoke(context, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(cause);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not call " + name + " on the payload context", e);
            }
        }

        /** The ServerLevel for a dimension key, through the running server. */
        private Object serverLevel(Object dimension) {
            try {
                Object server = Class.forName("net.neoforged.neoforge.server.ServerLifecycleHooks",
                        false, loader).getMethod("getCurrentServer").invoke(null);
                return method(server.getClass(), "getLevel", 1, false).invoke(server, dimension);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not find the level for " + dimension, e);
            }
        }

        private Object proxy(Class<?> type, InvocationHandler handler) {
            return Proxy.newProxyInstance(loader, new Class<?>[]{type}, handler);
        }

        /** Calls a NeoForge send method; the trailing varargs array is always empty. */
        private void invokeStatic(Class<?> owner, String name, Object... leading) {
            try {
                Object[] args = Arrays.copyOf(leading, leading.length + 1);
                args[leading.length] = Array.newInstance(payloadInterface, 0);
                method(owner, name, args.length, true).invoke(null, args);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("NeoForge rejected a Forge network message",
                        e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not send a Forge network message", e);
            }
        }

        private static Object call(Object target, String name, Object... args) {
            try {
                return method(target.getClass(), name, args.length, false).invoke(target, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(cause);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not call " + name + " on " + target, e);
            }
        }

        /** The public method with this name and arity, found on the class or an interface it implements. */
        static Method method(Class<?> owner, String name, int arity, boolean isStatic)
                throws NoSuchMethodException {
            for (Method candidate : owner.getMethods()) {
                if (candidate.getName().equals(name) && candidate.getParameterCount() == arity
                        && Modifier.isStatic(candidate.getModifiers()) == isStatic) {
                    return candidate;
                }
            }
            throw new NoSuchMethodException(owner.getName() + "." + name + " with " + arity
                    + " parameter(s)");
        }

        /** Answers Object's methods for a proxy, and runs interface default methods unchanged. */
        static Object objectMethod(Object proxy, Method method, Object[] args, Object identity)
                throws Throwable {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> String.valueOf(identity);
                default -> {
                    if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args);
                    throw new UnsupportedOperationException(method.toString());
                }
            };
        }
    }

    /** Forge 26.x's own SimpleChannel, reached by reflection: each old channel opens one and adds its messages. */
    static final class ForgeChannels {
        private final Class<?> builder;
        private final Class<?> distributor;
        private final Class<?> packetTarget;
        private final Class<?> targetPoint;

        ForgeChannels(Class<?> builder, Class<?> distributor, Class<?> packetTarget, Class<?> targetPoint) {
            this.builder = builder;
            this.distributor = distributor;
            this.packetTarget = packetTarget;
            this.targetPoint = targetPoint;
        }

        /** Forge's channel API, or null on NeoForge, which has payloads instead, and on older Forge. */
        static ForgeChannels resolve(ClassLoader loader) {
            try {
                Class.forName("net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent", false, loader);
                return null;
            } catch (ClassNotFoundException notNeoForge) {
                // Forge, or no loader at all.
            }
            try {
                String network = "net.minecraftforge.network.";
                return new ForgeChannels(Class.forName(network + "ChannelBuilder", false, loader),
                        Class.forName(network + "PacketDistributor", false, loader),
                        Class.forName(network + "PacketDistributor$PacketTarget", false, loader),
                        Class.forName(network + "PacketDistributor$TargetPoint", false, loader));
            } catch (ClassNotFoundException olderForge) {
                return null;
            }
        }

        /** A Forge channel under the old channel's id, optional on both sides as an old one was to a missing peer. */
        Object open(Object channelId, String protocolVersion) {
            try {
                Method named = null;
                for (Method candidate : builder.getMethods()) {
                    if (candidate.getName().equals("named") && candidate.getParameterCount() == 1
                            && candidate.getParameterTypes()[0].isInstance(channelId)) {
                        named = candidate;
                    }
                }
                if (named == null) throw new NoSuchMethodException("ChannelBuilder.named for " + channelId);
                Object channel = named.invoke(null, channelId);
                channel = builder.getMethod("networkProtocolVersion", int.class).invoke(channel, numericVersion(protocolVersion));
                channel = builder.getMethod("optional").invoke(channel);
                return builder.getMethod("simpleChannel").invoke(channel);
            } catch (ReflectiveOperationException e) {
                throw failure("open Forge network channel " + channelId, e);
            }
        }

        /** Adds one old message, with its own id, encoder, decoder, and network-thread handler. */
        void add(Object channel, PacketRegistration<?> registration) {
            try {
                Object message = channel.getClass().getMethod("messageBuilder", Class.class, int.class)
                        .invoke(channel, registration.messageType(), registration.id());
                Class<?> messageBuilder = message.getClass();
                message = messageBuilder.getMethod("encoder", BiConsumer.class).invoke(message, registration.encoder());
                message = messageBuilder.getMethod("decoder", Function.class).invoke(message, registration.decoder());
                BiConsumer<Object, Object> handler = (received, context) ->
                        registration.handle(received, new Context(context, this));
                message = messageBuilder.getMethod("consumerNetworkThread", BiConsumer.class).invoke(message, handler);
                messageBuilder.getMethod("add").invoke(message);
            } catch (ReflectiveOperationException e) {
                throw failure("add " + registration.messageType().getName() + " to a Forge network channel", e);
            }
        }

        void sendToServer(Object channel, Object message) {
            send(channel, message, target("SERVER", null));
        }

        /** The old target kinds map onto Forge's own distributor constants of the same names. */
        void send(Object channel, PacketDistributor.PacketTarget target, Object message) {
            send(channel, message, target(target.kind(), target.argument()));
        }

        void sendToConnection(Object channel, Object message, Object connection) {
            invokeTwo(channel, "send", message, connection);
        }

        void reply(Object channel, Object message, Object context) {
            invokeTwo(channel, "reply", message, context);
        }

        @SuppressWarnings("unchecked")
        CompletableFuture<Void> enqueueWork(Object context, Runnable work) {
            return (CompletableFuture<Void>) contextCall(context, "enqueueWork", work);
        }

        Object contextCall(Object context, String name, Object... args) {
            try {
                return PayloadBridge.method(context.getClass(), name, args.length, false).invoke(context, args);
            } catch (InvocationTargetException e) {
                throw rethrown(e);
            } catch (ReflectiveOperationException e) {
                throw failure("call " + name + " on a Forge network context", e);
            }
        }

        private void send(Object channel, Object message, Object target) {
            try {
                channel.getClass().getMethod("send", Object.class, packetTarget).invoke(channel, message, target);
            } catch (InvocationTargetException e) {
                throw rethrown(e);
            } catch (ReflectiveOperationException e) {
                throw failure("send a Forge network message", e);
            }
        }

        private Object target(String kind, Object argument) {
            try {
                Object constant = distributor.getField(kind).get(null);
                if (kind.equals("ALL") || kind.equals("SERVER")) {
                    return distributor.getMethod("noArg").invoke(constant);
                }
                if (argument instanceof PacketDistributor.TargetPoint point) argument = targetPoint(point);
                return distributor.getMethod("with", Object.class).invoke(constant, argument);
            } catch (ReflectiveOperationException e) {
                throw failure("build the Forge packet target " + kind, e);
            }
        }

        /** Forge's TargetPoint, which also takes the radius squared. */
        private Object targetPoint(PacketDistributor.TargetPoint point) throws ReflectiveOperationException {
            for (var constructor : targetPoint.getConstructors()) {
                int count = constructor.getParameterCount();
                if (point.excluded() == null && count == 5) {
                    return constructor.newInstance(point.x(), point.y(), point.z(), point.r2(), point.dimension());
                }
                if (point.excluded() != null && count == 6) {
                    return constructor.newInstance(point.excluded(), point.x(), point.y(), point.z(), point.r2(),
                            point.dimension());
                }
            }
            throw new NoSuchMethodException("PacketDistributor.TargetPoint constructor");
        }

        /** Calls the two-argument method whose second parameter takes {@code second}. */
        private static void invokeTwo(Object target, String name, Object first, Object second) {
            try {
                for (Method method : target.getClass().getMethods()) {
                    if (method.getName().equals(name) && method.getParameterCount() == 2
                            && method.getParameterTypes()[1].isInstance(second)) {
                        method.invoke(target, first, second);
                        return;
                    }
                }
                throw new NoSuchMethodException(target.getClass().getName() + "." + name);
            } catch (InvocationTargetException e) {
                throw rethrown(e);
            } catch (ReflectiveOperationException e) {
                throw failure("call " + name + " on a Forge network channel", e);
            }
        }

        private static int numericVersion(String version) {
            try {
                return Integer.parseInt(version);
            } catch (NumberFormatException notANumber) {
                return String.valueOf(version).hashCode();
            }
        }

        private static RuntimeException rethrown(InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) return runtime;
            if (cause instanceof Error error) throw error;
            return new IllegalStateException(cause);
        }

        private static IllegalStateException failure(String action, ReflectiveOperationException e) {
            return new IllegalStateException("Retromod could not " + action + " through Forge's networking API", e);
        }
    }

    /** A Forge message carried as a NeoForge payload. */
    private record Payload(Object type, PacketRegistration<?> registration, Object message)
            implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("type") && method.getParameterCount() == 0) return type;
            return PayloadBridge.objectMethod(proxy, method, args, message);
        }
    }

    private static Payload payloadOf(Object payload) {
        return (Payload) Proxy.getInvocationHandler(payload);
    }

    /** {@code StreamCodec}: a VarInt message id, then the mod's own encoding. */
    private record CodecHandler(PayloadBridge payloads, SimpleChannelWrapper channel)
            implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("decode") && method.getParameterCount() == 1) {
                Object buffer = args[0];
                int id = (Integer) PayloadBridge.call(buffer, "readVarInt");
                PacketRegistration<?> registration = channel.registration(id);
                Object message = registration.decoder().apply(buffer);
                return payloads.payload(channel, registration, message);
            }
            if (method.getName().equals("encode") && method.getParameterCount() == 2) {
                Object buffer = args[0];
                Payload payload = payloadOf(args[1]);
                PayloadBridge.method(buffer.getClass(), "writeVarInt", 1, false)
                        .invoke(buffer, payload.registration().id());
                payload.registration().encode(payload.message(), buffer);
                return null;
            }
            return PayloadBridge.objectMethod(proxy, method, args, "Forge codec " + channel.channelId());
        }
    }

    /** {@code IPayloadHandler}: hands the decoded message to the mod's Forge consumer. */
    private record HandlerHandler(PayloadBridge payloads) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("handle") && method.getParameterCount() == 2) {
                Payload payload = payloadOf(args[0]);
                payload.registration().handle(payload.message(), new Context(args[1]));
                return null;
            }
            return PayloadBridge.objectMethod(proxy, method, args, "Forge payload handler");
        }
    }
}
