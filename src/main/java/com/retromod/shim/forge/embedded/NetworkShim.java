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

    private NetworkShim() {}

    /** Mirrors NetworkRegistry.newSimpleChannel. */
    public static Object newSimpleChannel(Object channelId,
            Supplier<String> networkProtocolVersion,
            Predicate<String> clientAcceptedVersions,
            Predicate<String> serverAcceptedVersions) {
        String version = networkProtocolVersion == null ? "1" : networkProtocolVersion.get();
        SimpleChannelWrapper channel = CHANNELS.computeIfAbsent(String.valueOf(channelId),
                key -> new SimpleChannelWrapper(channelId, version));
        if (bridge != null) {
            LOGGER.warn("Forge network channel {} was created after NeoForge registered its "
                    + "network payloads, so its messages cannot be sent.", channelId);
        }
        return channel;
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
    }

    private static PayloadBridge requireBridge() {
        PayloadBridge current = bridge;
        if (current == null) {
            throw new IllegalStateException("Forge network messages cannot be sent before "
                    + "NeoForge registers its network payloads.");
        }
        return current;
    }

    /** Mirrors Forge's SimpleChannel. */
    public static class SimpleChannelWrapper {
        private final Object channelId;
        private final String protocolVersion;
        private final Map<Integer, PacketRegistration<?>> registrations = new ConcurrentHashMap<>();
        private final Map<Class<?>, PacketRegistration<?>> byType = new ConcurrentHashMap<>();
        private volatile Object payloadType;
        private volatile Object payloadHandler;

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
            return null;
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
            requireBridge().sendToServer(payload(message));
        }

        /** {@code send(PacketDistributor.PLAYER.with(() -> player), message)} and the other targets. */
        public void send(Object target, Object message) {
            if (!(target instanceof PacketDistributor.PacketTarget packetTarget)) {
                throw new IllegalArgumentException("Unsupported Forge packet target: " + target);
            }
            requireBridge().send(packetTarget, payload(message));
        }

        /** {@code sendTo(message, connection, direction)}: writes the packet to one connection. */
        public void sendTo(Object message, Object connection, NetworkDirection direction) {
            requireBridge().sendToConnection(connection, payload(message),
                    direction == NetworkDirection.PLAY_TO_SERVER
                            || direction == NetworkDirection.LOGIN_TO_SERVER);
        }

        /** {@code reply(message, context)}: answers on the connection the request came from. */
        public void reply(Object message, Context context) {
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

        Context(Object payloadContext) {
            this.payloadContext = payloadContext;
        }

        public CompletableFuture<Void> enqueueWork(Runnable work) {
            return requireBridge().enqueueWork(payloadContext, work);
        }

        /** The sending player on the server, and null on the client, as in Forge. */
        public Object getSender() {
            return getDirection() == NetworkDirection.PLAY_TO_SERVER
                    ? requireBridge().player(payloadContext) : null;
        }

        public Object getNetworkManager() {
            return requireBridge().connection(payloadContext);
        }

        public NetworkDirection getDirection() {
            return requireBridge().isServerbound(payloadContext)
                    ? NetworkDirection.PLAY_TO_SERVER : NetworkDirection.PLAY_TO_CLIENT;
        }

        /** NeoForge has no handled flag; a handler that returns has handled its payload. */
        public void setPacketHandled(boolean handled) {
        }

        public boolean getPacketHandled() {
            return true;
        }
    }

    /** Fluent packet registration builder. */
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
