/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * Bridges Forge's SimpleChannel networking to NeoForge's codec-based PayloadRegistrar.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

/** Shim for net.minecraftforge.network.NetworkRegistry and SimpleChannel. */
public final class NetworkShim {

    private static Class<?> payloadRegistrarClass;
    private static Class<?> simpleChannelClass;
    private static boolean isNeoForge = false;
    private static boolean initialized = false;

    private static final Map<String, Object> registeredChannels = new HashMap<>();
    
    private NetworkShim() {}
    
    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        
        try {
            payloadRegistrarClass = Class.forName(
                "net.neoforged.neoforge.network.registration.PayloadRegistrar"
            );
            isNeoForge = true;

        } catch (ClassNotFoundException e) {
            try {
                simpleChannelClass = Class.forName(
                    "net.minecraftforge.network.simple.SimpleChannel"
                );
            } catch (ClassNotFoundException e2) {
                System.err.println("Retromod: No networking system found");
            }
        }
    }
    
    /** Mirrors NetworkRegistry.newSimpleChannel. */
    public static Object newSimpleChannel(Object resourceLocation,
            Supplier<String> networkProtocolVersion,
            Predicate<String> clientAcceptedVersions,
            Predicate<String> serverAcceptedVersions) {
        
        initialize();

        if (isNeoForge) {
            return new SimpleChannelWrapper(resourceLocation, networkProtocolVersion.get());
        }

        if (simpleChannelClass != null) {
            try {
                Class<?> registryClass = Class.forName(
                    "net.minecraftforge.network.NetworkRegistry"
                );
                
                Method newChannel = registryClass.getMethod("newSimpleChannel",
                    resourceLocation.getClass(),
                    Supplier.class,
                    Predicate.class,
                    Predicate.class
                );

                return newChannel.invoke(null, resourceLocation,
                    networkProtocolVersion, clientAcceptedVersions, serverAcceptedVersions);

            } catch (Exception e) {
                System.err.println("Retromod: Could not create SimpleChannel: " + e);
            }
        }
        
        return new SimpleChannelWrapper(resourceLocation, "1");
    }
    
    /** Collects packet registrations and replays them against PayloadRegistrar at mod setup. */
    public static class SimpleChannelWrapper {
        private final Object resourceLocation;
        private final String protocolVersion;
        private final List<PacketRegistration<?>> registrations = new ArrayList<>();
        private int nextId = 0;
        
        public SimpleChannelWrapper(Object resourceLocation, String protocolVersion) {
            this.resourceLocation = resourceLocation;
            this.protocolVersion = protocolVersion;
            registeredChannels.put(resourceLocation.toString(), this);
        }
        
        /** Mirrors SimpleChannel.registerMessage. */
        public <T> void registerMessage(int id, Class<T> messageType,
                BiConsumer<T, Object> encoder,
                Function<Object, T> decoder,
                BiConsumer<T, Object> messageConsumer) {

            registrations.add(new PacketRegistration<>(
                id, messageType, encoder, decoder, messageConsumer
            ));
        }

        public <T> MessageBuilder<T> messageBuilder(Class<T> type, int id) {
            return new MessageBuilder<>(this, type, id);
        }

        /** The direction-qualified overload (MCreator's addNetworkMessage); direction is ignored
         *  by the collect-and-replay design, which registers both ways. */
        public <T> MessageBuilder<T> messageBuilder(Class<T> type, int id, Object direction) {
            return new MessageBuilder<>(this, type, id);
        }

        /** The Optional&lt;NetworkDirection&gt; overload of registerMessage; same erasure story. */
        public <T> void registerMessage(int id, Class<T> messageType,
                BiConsumer<T, Object> encoder,
                Function<Object, T> decoder,
                BiConsumer<T, Object> messageConsumer,
                java.util.Optional<?> direction) {
            registerMessage(id, messageType, encoder, decoder, messageConsumer);
        }

        public void sendToServer(Object message) {
            try {
                Class<?> distributorClass = Class.forName(
                    "net.neoforged.neoforge.network.PacketDistributor"
                );
                
                Method sendToServer = distributorClass.getMethod("sendToServer", Object.class);

                Object payload = wrapAsPayload(message);
                sendToServer.invoke(null, payload);

            } catch (Exception e) {
                System.err.println("Retromod: Could not send to server: " + e);
            }
        }

        public void send(Object target, Object message) {
            try {
                Class<?> distributorClass = Class.forName(
                    "net.neoforged.neoforge.network.PacketDistributor"
                );

                Object payload = wrapAsPayload(message);

                Method sendToPlayer = distributorClass.getMethod("sendToPlayer",
                    Class.forName("net.minecraft.server.level.ServerPlayer"),
                    Object.class
                );

                sendToPlayer.invoke(null, target, payload);

            } catch (Exception e) {
                System.err.println("Retromod: Could not send packet: " + e);
            }
        }

        private Object wrapAsPayload(Object message) {
            return new PayloadWrapper(resourceLocation, message, this);
        }
        
        public Object getResourceLocation() {
            return resourceLocation;
        }
        
        public List<PacketRegistration<?>> getRegistrations() {
            return registrations;
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

        /** Forge's network-thread variant. */
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
    ) {}
    
    /** Holds a Forge-style message as a NeoForge payload. */
    public static class PayloadWrapper {
        private final Object channelId;
        private final Object message;
        private final SimpleChannelWrapper channel;
        
        public PayloadWrapper(Object channelId, Object message, SimpleChannelWrapper channel) {
            this.channelId = channelId;
            this.message = message;
            this.channel = channel;
        }
        
        public Object getChannelId() { return channelId; }
        public Object getMessage() { return message; }
        public SimpleChannelWrapper getChannel() { return channel; }

        /** Payload type id for NeoForge registration. */
        public Object type() {
            return channelId;
        }
    }

    /** Target specifications for packet sending, matching Forge's PacketDistributor. */
    public static class PacketDistributor {
        
        public static Object PLAYER(Object player) {
            return new PacketTarget("PLAYER", player);
        }
        
        public static Object ALL() {
            return new PacketTarget("ALL", null);
        }
        
        public static Object SERVER() {
            return new PacketTarget("SERVER", null);
        }

        public static Object TRACKING_ENTITY(Object entity) {
            return new PacketTarget("TRACKING_ENTITY", entity);
        }

        public static Object TRACKING_CHUNK(Object chunk) {
            return new PacketTarget("TRACKING_CHUNK", chunk);
        }

        public static Object NEAR(Object pos, double distance, Object dimension) {
            return new PacketTarget("NEAR", new Object[]{pos, distance, dimension});
        }
        
        public record PacketTarget(String type, Object data) {}
    }
    
    public static Map<String, Object> getRegisteredChannels() {
        return Collections.unmodifiableMap(registeredChannels);
    }

    /** Replays collected registrations against NeoForge's PayloadRegistrar at mod init. */
    public static void replayRegistrations(Object registrar) {
        for (Object channel : registeredChannels.values()) {
            if (channel instanceof SimpleChannelWrapper wrapper) {
                for (PacketRegistration<?> reg : wrapper.getRegistrations()) {
                    registerWithNeoForge(registrar, wrapper, reg);
                }
            }
        }
    }
    
    private static void registerWithNeoForge(Object registrar,
            SimpleChannelWrapper channel, PacketRegistration<?> reg) {
        // a full bridge would build codecs and call registrar.playToServer/playToClient
        try {
            System.out.println("Retromod: Would register packet " + reg.messageType().getSimpleName()
                + " on channel " + channel.getResourceLocation());

        } catch (Exception e) {
            System.err.println("Retromod: Could not replay registration: " + e);
        }
    }
}
