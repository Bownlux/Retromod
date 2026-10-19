/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.fabric.embedded;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Bridge between old-style Fabric networking API (channel-based) and new-style (payload-based).
 *
 * <h2>The networking API change</h2>
 * <p><b>Old API (channel-based, removed):</b> Mods registered a handler for a named channel
 * (an Identifier like "mymod:sync"). When packets arrived on that channel, the handler
 * received raw bytes (PacketByteBuf) plus context objects for server, player, etc.</p>
 *
 * <p><b>New API (payload-based):</b> Mods define a CustomPayload class with a Type ID,
 * register a PlayPayloadHandler, and receive a typed payload + Context object.
 * The Context consolidates all the old separate parameters (server, player, sender)
 * into a single object with getter methods.</p>
 *
 * <h2>How the bridge works</h2>
 * <p>We create a JDK Proxy implementing PlayPayloadHandler that delegates to the old
 * PlayChannelHandler. When the new handler's receive(payload, context) is called, we:</p>
 * <ol>
 *   <li>Extract server, player, connection from the Context via reflection</li>
 *   <li>Extract raw bytes from the CustomPayload (if available)</li>
 *   <li>Call the old handler's receive(server, player, connection, buf, sender)</li>
 * </ol>
 *
 * <p><b>Limitations:</b> Old-style send() calls (raw bytes on a channel) cannot be bridged
 * because the new API requires a registered CustomPayload type. These are no-op'd with a
 * warning log, and the ByteBuf is released to prevent memory leaks.</p>
 *
 * <p>While the loader API runtime is on, every entry point forwards to its channels instead, so a
 * jar translated before the runtime still registers, sends and receives.</p>
 */
public class FabricNetworkingBridge {

    /** Lets the loader API runtime take over this bridge while it is on. */
    public interface Delegate {
        boolean registerServerGlobalReceiver(Object channelId, Object handler);

        boolean registerClientGlobalReceiver(Object channelId, Object handler);

        void serverSend(Object player, Object channelId, Object buf);

        void clientSend(Object channelId, Object buf);
    }

    private static volatile Delegate delegate;

    /** Installs the runtime's channels; every entry point then forwards to them. */
    public static void installDelegate(Delegate runtime) {
        delegate = runtime;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-NetworkBridge");

    // Cached reflection lookups, initialized once on first use.
    // We use reflection because the Fabric API classes aren't on Retromod's
    // compile classpath (Retromod is loader-agnostic at compile time).
    private static volatile boolean initialized = false;
    // BUG: bridgeAvailable is written inside synchronized initialize() but read outside
    // synchronization in registerServerGlobalReceiver/registerClientGlobalReceiver.
    // Should be volatile to ensure visibility across threads.
    private static volatile boolean bridgeAvailable = false;

    // Server-side: registerGlobalReceiver(CustomPayload.Type, PlayPayloadHandler) method
    private static Method serverRegisterMethod;
    private static Class<?> customPayloadIdClass;      // CustomPayload.Type class
    private static Class<?> serverPayloadHandlerClass;  // PlayPayloadHandler interface
    private static Class<?> serverContextClass;         // Context interface (has player(), server())

    // Client-side: same pattern but for ClientPlayNetworking
    private static Method clientRegisterMethod;
    private static Class<?> clientPayloadHandlerClass;
    private static Class<?> clientContextClass;

    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();

            // Load the new API classes
            Class<?> serverNetworking = cl.loadClass(
                "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking");
            Class<?> clientNetworking = cl.loadClass(
                "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");

            customPayloadIdClass = cl.loadClass(
                "net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type");

            // Find the new handler interfaces
            for (Class<?> inner : serverNetworking.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("PlayPayloadHandler")) {
                    serverPayloadHandlerClass = inner;
                }
                if (inner.getSimpleName().equals("Context")) {
                    serverContextClass = inner;
                }
            }
            for (Class<?> inner : clientNetworking.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("PlayPayloadHandler")) {
                    clientPayloadHandlerClass = inner;
                }
                if (inner.getSimpleName().equals("Context")) {
                    clientContextClass = inner;
                }
            }

            if (serverPayloadHandlerClass != null && customPayloadIdClass != null) {
                // Find: registerGlobalReceiver(CustomPayload.Id, PlayPayloadHandler) -> boolean
                for (Method m : serverNetworking.getDeclaredMethods()) {
                    if (m.getName().equals("registerGlobalReceiver")
                            && m.getParameterCount() == 2
                            && m.getParameterTypes()[0] == customPayloadIdClass
                            && m.getParameterTypes()[1] == serverPayloadHandlerClass) {
                        serverRegisterMethod = m;
                        break;
                    }
                }
            }

            if (clientPayloadHandlerClass != null && customPayloadIdClass != null) {
                for (Method m : clientNetworking.getDeclaredMethods()) {
                    if (m.getName().equals("registerGlobalReceiver")
                            && m.getParameterCount() == 2
                            && m.getParameterTypes()[0] == customPayloadIdClass
                            && m.getParameterTypes()[1] == clientPayloadHandlerClass) {
                        clientRegisterMethod = m;
                        break;
                    }
                }
            }

            bridgeAvailable = serverRegisterMethod != null;
            if (bridgeAvailable) {
                LOGGER.info("Fabric networking bridge initialized - old-style handlers will be adapted");
            } else {
                LOGGER.warn("Fabric networking bridge: could not find new registerGlobalReceiver method");
            }
        } catch (Exception e) {
            LOGGER.warn("Fabric networking bridge initialization failed: {}", e.getMessage());
            bridgeAvailable = false;
        }
    }

    /**
     * Bridge for ServerPlayNetworking.registerGlobalReceiver(Identifier, PlayChannelHandler).
     * Called by transformed mod bytecode via method redirect.
     *
     * @param channelId the channel Identifier (net.minecraft.resources.Identifier)
     * @param handler the old-style PlayChannelHandler (our ServerPlayChannelHandler interface)
     * @return true if registration succeeded
     */
    public static boolean registerServerGlobalReceiver(Object channelId, Object handler) {
        Delegate runtime = delegate;
        if (runtime != null) return runtime.registerServerGlobalReceiver(channelId, handler);
        initialize();

        String channelStr = channelId.toString();
        LOGGER.info("Bridging old-style server receiver for channel: {}", channelStr);

        if (!bridgeAvailable || serverRegisterMethod == null || serverContextClass == null) {
            LOGGER.warn("Cannot bridge server receiver for '{}' - new API not available. " +
                "Server-client syncing for this channel will not work.", channelStr);
            return false;
        }

        try {
            // Create a new-style PlayPayloadHandler that wraps the old handler.
            // The new handler receives (payload, context) and we extract the old params from context.
            ServerPlayChannelHandler oldHandler = (ServerPlayChannelHandler) handler;

            // Create a proxy for PlayPayloadHandler that bridges to the old handler
            Object newHandler = createServerPayloadHandler(oldHandler, channelStr);
            if (newHandler == null) {
                LOGGER.warn("Failed to create payload handler proxy for channel: {}", channelStr);
                return false;
            }

            // Create CustomPayload.Id from the channel Identifier
            Object payloadId = createPayloadId(channelId);
            if (payloadId == null) {
                LOGGER.warn("Failed to create payload ID for channel: {}", channelStr);
                return false;
            }

            // Register via reflection
            Object result = serverRegisterMethod.invoke(null, payloadId, newHandler);
            LOGGER.info("Successfully bridged server receiver for channel: {}", channelStr);
            return result instanceof Boolean b ? b : true;

        } catch (Exception e) {
            LOGGER.warn("Failed to bridge server receiver for channel '{}': {}",
                channelStr, e.getMessage());
            LOGGER.debug("Bridge failure details:", e);
            return false;
        }
    }

    /**
     * Bridge for ClientPlayNetworking.registerGlobalReceiver(Identifier, PlayChannelHandler).
     */
    public static boolean registerClientGlobalReceiver(Object channelId, Object handler) {
        Delegate runtime = delegate;
        if (runtime != null) return runtime.registerClientGlobalReceiver(channelId, handler);
        initialize();

        String channelStr = channelId.toString();
        LOGGER.info("Bridging old-style client receiver for channel: {}", channelStr);

        if (!bridgeAvailable || clientRegisterMethod == null || clientContextClass == null) {
            LOGGER.warn("Cannot bridge client receiver for '{}' - new API not available. " +
                "Client networking for this channel will not work.", channelStr);
            return false;
        }

        try {
            ClientPlayChannelHandler oldHandler = (ClientPlayChannelHandler) handler;

            Object newHandler = createClientPayloadHandler(oldHandler, channelStr);
            if (newHandler == null) {
                LOGGER.warn("Failed to create client payload handler for channel: {}", channelStr);
                return false;
            }

            Object payloadId = createPayloadId(channelId);
            if (payloadId == null) {
                LOGGER.warn("Failed to create client payload ID for channel: {}", channelStr);
                return false;
            }

            Object result = clientRegisterMethod.invoke(null, payloadId, newHandler);
            LOGGER.info("Successfully bridged client receiver for channel: {}", channelStr);
            return result instanceof Boolean b ? b : true;

        } catch (Exception e) {
            LOGGER.warn("Failed to bridge client receiver for channel '{}': {}",
                channelStr, e.getMessage());
            return false;
        }
    }

    /**
     * Create a new-style PlayPayloadHandler proxy that delegates to the old ServerPlayChannelHandler.
     * Extracts server, player, connection, and sender from the new Context object.
     */
    private static Object createServerPayloadHandler(ServerPlayChannelHandler oldHandler, String channel) {
        if (serverPayloadHandlerClass == null || serverContextClass == null) return null;

        try {
            // Find Context methods via reflection
            Method contextPlayer = serverContextClass.getMethod("player");
            Method contextServer = findMethod(serverContextClass, "server");
            Method contextResponder = findMethod(serverContextClass, "responseSender");

            return Proxy.newProxyInstance(
                serverPayloadHandlerClass.getClassLoader(),
                new Class<?>[]{ serverPayloadHandlerClass },
                (proxy, method, args) -> {
                    if ("receive".equals(method.getName()) && args != null && args.length == 2) {
                        Object payload = args[0];
                        Object context = args[1];

                        try {
                            Object player = contextPlayer.invoke(context);
                            Object server = contextServer != null ? contextServer.invoke(context) : null;
                            Object connection = getPlayerConnection(player);
                            Object sender = contextResponder != null ? contextResponder.invoke(context) : null;

                            // Extract raw bytes from the payload if possible
                            Object buf = extractByteBuf(payload);

                            oldHandler.receive(server, player, connection, buf, sender);
                        } catch (Exception e) {
                            LOGGER.warn("Error in bridged server handler for channel '{}': {}",
                                channel, e.getMessage());
                        }
                        return null;
                    }
                    // Default Object methods
                    if ("toString".equals(method.getName())) {
                        return "Retromod-BridgedHandler[" + channel + "]";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    return null;
                }
            );
        } catch (Exception e) {
            LOGGER.debug("Failed to create server payload handler proxy: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Create a new-style PlayPayloadHandler proxy for client-side.
     */
    private static Object createClientPayloadHandler(ClientPlayChannelHandler oldHandler, String channel) {
        if (clientPayloadHandlerClass == null || clientContextClass == null) return null;

        try {
            Method contextClient = findMethod(clientContextClass, "client");
            Method contextResponder = findMethod(clientContextClass, "responseSender");

            return Proxy.newProxyInstance(
                clientPayloadHandlerClass.getClassLoader(),
                new Class<?>[]{ clientPayloadHandlerClass },
                (proxy, method, args) -> {
                    if ("receive".equals(method.getName()) && args != null && args.length == 2) {
                        Object payload = args[0];
                        Object context = args[1];

                        try {
                            Object client = contextClient != null ? contextClient.invoke(context) : null;
                            Object handler2 = getClientPlayNetworkHandler(client);
                            Object sender = contextResponder != null ? contextResponder.invoke(context) : null;
                            Object buf = extractByteBuf(payload);

                            oldHandler.receive(client, handler2, buf, sender);
                        } catch (Exception e) {
                            LOGGER.warn("Error in bridged client handler for channel '{}': {}",
                                channel, e.getMessage());
                        }
                        return null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "Retromod-BridgedClientHandler[" + channel + "]";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    return null;
                }
            );
        } catch (Exception e) {
            LOGGER.debug("Failed to create client payload handler proxy: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Create a CustomPayload.Id (or CustomPayload.Type) from an Identifier.
     * Uses reflection: new CustomPayload.Type(identifier) or CustomPayload.Id(identifier)
     */
    private static Object createPayloadId(Object identifier) {
        if (customPayloadIdClass == null) return null;
        try {
            // Try to find a constructor that takes an Identifier/ResourceLocation
            for (var ctor : customPayloadIdClass.getConstructors()) {
                if (ctor.getParameterCount() == 1) {
                    Class<?> paramType = ctor.getParameterTypes()[0];
                    if (paramType.isAssignableFrom(identifier.getClass())) {
                        return ctor.newInstance(identifier);
                    }
                }
            }
            LOGGER.debug("No suitable constructor found on {} for {}",
                customPayloadIdClass.getName(), identifier.getClass().getName());
        } catch (Exception e) {
            LOGGER.debug("Failed to create payload ID: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Try to get the player's network connection (ServerGamePacketListenerImpl).
     */
    private static Object getPlayerConnection(Object player) {
        if (player == null) return null;
        try {
            // Try 'connection' field (Mojang name in 26.1)
            var field = player.getClass().getField("connection");
            return field.get(player);
        } catch (NoSuchFieldException e) {
            // Try getter methods
            try {
                return player.getClass().getMethod("connection").invoke(player);
            } catch (Exception e2) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Try to get the client's play network handler.
     */
    private static Object getClientPlayNetworkHandler(Object client) {
        if (client == null) return null;
        try {
            return client.getClass().getMethod("getConnection").invoke(client);
        } catch (Exception e) {
            try {
                return client.getClass().getField("connection").get(client);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /**
     * Try to extract a FriendlyByteBuf / PacketByteBuf from a CustomPayload.
     * The payload might wrap raw bytes that we can pass to the old handler.
     */
    private static Object extractByteBuf(Object payload) {
        if (payload == null) return null;
        try {
            // Try common getter names for the raw data
            for (String methodName : new String[]{"data", "buf", "getData", "getBuf"}) {
                try {
                    Method m = payload.getClass().getMethod(methodName);
                    return m.invoke(payload);
                } catch (NoSuchMethodException ignored) {}
            }
            // Try fields, but only access ByteBuf-typed fields to limit reflection scope
            for (var field : payload.getClass().getDeclaredFields()) {
                if (field.getType().getSimpleName().contains("ByteBuf")
                        || field.getType().getSimpleName().contains("FriendlyByte")) {
                    field.setAccessible(true);
                    Object val = field.get(payload);
                    if (val != null) {
                        return val;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not extract ByteBuf from payload: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Bridge for ClientPlayNetworking.send(Identifier, FriendlyByteBuf).
     * Old API sends raw bytes on a named channel. In 26.1 this is removed;
     * the new API requires CustomPacketPayload. We log a warning and no-op,
     * since the receiving end also won't be registered in the old way.
     *
     * This prevents NoSuchMethodError crashes while the mod still loads.
     */
    public static void clientSend(Object channelId, Object buf) {
        Delegate runtime = delegate;
        if (runtime != null) {
            runtime.clientSend(channelId, buf);
            return;
        }
        initialize();
        LOGGER.warn("Bridged clientSend({}) - old channel-based send is not supported in 26.1. " +
            "Packet will be dropped.", channelId);
        // Release the ByteBuf if it's a Netty buffer to prevent leaks
        releaseBuf(buf);
    }

    /**
     * Bridge for ServerPlayNetworking.send(ServerPlayer, Identifier, FriendlyByteBuf).
     */
    public static void serverSend(Object player, Object channelId, Object buf) {
        Delegate runtime = delegate;
        if (runtime != null) {
            runtime.serverSend(player, channelId, buf);
            return;
        }
        initialize();
        LOGGER.warn("Bridged serverSend({}) - old channel-based send is not supported in 26.1. " +
            "Packet will be dropped.", channelId);
        releaseBuf(buf);
    }

    private static void releaseBuf(Object buf) {
        if (buf == null) return;
        try {
            // FriendlyByteBuf extends ByteBuf which has release()
            Method release = buf.getClass().getMethod("release");
            release.invoke(buf);
        } catch (Exception ignored) {}
    }

    private static Method findMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                return m;
            }
        }
        return null;
    }
}
