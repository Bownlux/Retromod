/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric.embedded;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the removed Fabric client networking v1 raw-channel API (id-keyed raw
 * {@code FriendlyByteBuf}, pre-1.20.5) onto 26.1's typed {@code CustomPacketPayload}. Per
 * channel id we lazily register a {@code Type(id)} plus a {@link java.lang.reflect.Proxy}-backed
 * {@code StreamCodec} copying raw bytes both ways; inbound packets are unwrapped and handed
 * to the mod's old 4-arg handler.
 *
 * <p>Reflection + Proxy only, so it compiles against {@code java.*} and embeds into a mod jar
 * with no unresolved references. A wrong name degrades to a logged no-op (networking inert)
 * rather than a {@code VerifyError}/{@code NoSuchMethodError}.
 *
 * <p>Written against 26.1.2 but not yet runtime-verified. See {@code docs-dev/cpn-v1-bridge-design.md}.
 */
public final class ClientPlayNetworkingV1Bridge {

    private ClientPlayNetworkingV1Bridge() {}

    private static final String TAG = "[Retromod/CPNv1] ";
    // Embedded into mod jars: no access to Retromod's shaded SLF4J, so use stdio.
    private static void info(String m) { System.out.println(TAG + m); }
    private static void warn(String m, Throwable t) {
        System.err.println(TAG + m + (t != null ? ": " + t : ""));
    }

    // Lazily-resolved 26.1 runtime handles.
    private static volatile boolean ready = false;
    private static volatile boolean initFailed = false;

    private static Class<?> cType, cStreamCodec, cCustomPayload, cPayloadHandler, cContext, cByteBuf;
    private static Constructor<?> typeCtor;        // CustomPacketPayload$Type(Identifier)
    private static Constructor<?> friendlyBufCtor; // FriendlyByteBuf(ByteBuf)
    private static Method mClientboundPlay, mServerboundPlay; // PayloadTypeRegistry statics
    private static Method mRegistryRegister;       // PayloadTypeRegistry.register(Type, StreamCodec)
    private static Method mNetRegisterReceiver;    // ClientPlayNetworking.registerGlobalReceiver(Type, PlayPayloadHandler)
    private static Method mNetSend;                // ClientPlayNetworking.send(CustomPacketPayload)
    private static Method mNetCanSend;             // ClientPlayNetworking.canSend(Type)   (nullable)
    private static Method mCtxPlayer, mCtxResponseSender;
    private static Method mBufReadableBytes, mBufReadBytes, mBufWriteBytes;
    private static Method mUnpooledWrapped;        // Unpooled.wrappedBuffer(byte[])
    private static Method mMcGetInstance, mMcGetConnection;

    // id -> cached Type instance; ids already registered with PayloadTypeRegistry
    private static final Map<String, Object> TYPE_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

    private static synchronized void ensureInit() {
        if (ready || initFailed) return;
        try {
            cType          = loadFirst("net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type",
                    "net.minecraft.class_8710$class_9154");
            cStreamCodec   = loadFirst("net.minecraft.network.codec.StreamCodec", "net.minecraft.class_9139");
            cCustomPayload = loadFirst("net.minecraft.network.protocol.common.custom.CustomPacketPayload",
                    "net.minecraft.class_8710");
            cByteBuf       = Class.forName("io.netty.buffer.ByteBuf");
            cPayloadHandler = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$PlayPayloadHandler");
            cContext        = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$Context");
            Class<?> cPayloadReg = Class.forName("net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry");
            Class<?> cClientNet  = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
            Class<?> cFriendly   = loadFirst("net.minecraft.network.FriendlyByteBuf", "net.minecraft.class_2540");
            Class<?> cUnpooled   = Class.forName("io.netty.buffer.Unpooled");
            Class<?> cMc         = loadFirst("net.minecraft.client.Minecraft", "net.minecraft.class_310");
            Class<?> cListener   = loadFirst("net.minecraft.client.multiplayer.ClientPacketListener",
                    "net.minecraft.class_634");

            typeCtor = singleArgCtor(cType);
            friendlyBufCtor = cFriendly.getConstructor(cByteBuf);

            mClientboundPlay = firstStaticByNames(cPayloadReg, "clientboundPlay", "playS2C");
            mServerboundPlay = firstStaticByNames(cPayloadReg, "serverboundPlay", "playC2S");
            mRegistryRegister = firstByNameAndArity(cPayloadReg, "register", 2);

            mNetRegisterReceiver = firstStaticByNameAndArity(cClientNet, "registerGlobalReceiver", 2);
            mNetSend = firstStaticByNameAndArity(cClientNet, "send", 1);
            mNetCanSend = firstStaticByNameAndArityOrNull(cClientNet, "canSend", 1);

            mCtxPlayer = cContext.getMethod("player");
            mCtxResponseSender = cContext.getMethod("responseSender");

            mBufReadableBytes = cByteBuf.getMethod("readableBytes");
            mBufReadBytes = cByteBuf.getMethod("readBytes", byte[].class);
            mBufWriteBytes = cByteBuf.getMethod("writeBytes", byte[].class);
            mUnpooledWrapped = cUnpooled.getMethod("wrappedBuffer", byte[].class);

            mMcGetInstance = staticNoArgReturning(cMc, cMc);
            mMcGetConnection = noArgReturning(cMc, cListener);

            if (mRegistryRegister == null || mNetRegisterReceiver == null || mNetSend == null) {
                throw new NoSuchMethodException("core 26.1 networking entrypoint not found");
            }
            ready = true;
            info("client-networking v1 bridge initialised");
        } catch (Throwable t) {
            initFailed = true;
            warn("init failed - client networking v1 bridge inert (mods load, custom packets dropped)", t);
        }
    }

    // Redirect targets take Object so the descriptor is (Ljava/lang/Object;...) and the verifier
    // accepts the rewritten call site's concrete types as Object subtypes.

    /** old {@code registerGlobalReceiver(Identifier, PlayChannelHandler) : boolean}. */
    public static boolean registerGlobalReceiver(Object id, Object oldHandler) {
        return doRegister(id, oldHandler);
    }

    /** old {@code registerReceiver(Identifier, PlayChannelHandler) : boolean} (best-effort global). */
    public static boolean registerReceiver(Object id, Object oldHandler) {
        return doRegister(id, oldHandler);
    }

    /** old {@code send(Identifier, FriendlyByteBuf) : void}. */
    public static void send(Object id, Object buf) {
        ensureInit();
        if (!ready) return;
        try {
            registerChannel(id);
            byte[] data = drainBytes(buf);
            Object payload = newRawPayload(typeFor(id), data);
            mNetSend.invoke(null, payload);
        } catch (Throwable t) {
            warn("send failed for channel " + id, t);
        }
    }

    /** old {@code canSend(Identifier) : boolean}. */
    public static boolean canSend(Object id) {
        ensureInit();
        if (!ready || mNetCanSend == null) return false;
        try {
            return (Boolean) mNetCanSend.invoke(null, typeFor(id));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean doRegister(Object id, Object oldHandler) {
        ensureInit();
        if (!ready) return false;
        try {
            registerChannel(id);
            Object newHandler = newReceiveAdapter(oldHandler);
            Object result = mNetRegisterReceiver.invoke(null, typeFor(id), newHandler);
            return result instanceof Boolean ? (Boolean) result : true;
        } catch (Throwable t) {
            warn("registerReceiver failed for channel " + id, t);
            return false;
        }
    }

    /** Cached {@code CustomPacketPayload.Type} for a channel id, built on first use. */
    private static Object typeFor(Object id) throws Exception {
        String key = String.valueOf(id);
        Object t = TYPE_CACHE.get(key);
        if (t == null) {
            t = typeCtor.newInstance(id);
            Object prev = TYPE_CACHE.putIfAbsent(key, t);
            if (prev != null) t = prev;
        }
        return t;
    }

    /** Register the raw-bytes codec on both play registries, once per id. */
    private static void registerChannel(Object id) {
        String key = String.valueOf(id);
        if (!REGISTERED.add(key)) return;
        try {
            Object type = typeFor(id);
            // Each codec is bound to this channel's Type so inbound packets route by payload.type().
            tryRegister(mClientboundPlay, type, newRawCodec(type));
            tryRegister(mServerboundPlay, type, newRawCodec(type));
        } catch (Throwable t) {
            warn("codec registration failed for channel " + key, t);
        }
    }

    private static void tryRegister(Method registryStatic, Object type, Object codec) {
        try {
            Object registry = registryStatic.invoke(null);
            mRegistryRegister.invoke(registry, type, codec);
        } catch (Throwable t) {
            // Already registered or frozen; the other direction may still take.
        }
    }

    /** {@code CustomPacketPayload} carrying (Type, raw bytes). */
    private static Object newRawPayload(Object type, byte[] data) {
        return Proxy.newProxyInstance(loader(), new Class<?>[]{cCustomPayload},
                new RawPayloadHandler(type, data));
    }

    /** {@code StreamCodec} bound to {@code type}, copying raw bytes both ways. */
    private static Object newRawCodec(Object type) {
        return Proxy.newProxyInstance(loader(), new Class<?>[]{cStreamCodec}, new RawCodecHandler(type));
    }

    /** {@code PlayPayloadHandler} that unwraps bytes and calls the mod's old 4-arg handler. */
    private static Object newReceiveAdapter(Object oldHandler) {
        return Proxy.newProxyInstance(loader(), new Class<?>[]{cPayloadHandler},
                new ReceiveAdapter(oldHandler));
    }

    private static ClassLoader loader() {
        return ClientPlayNetworkingV1Bridge.class.getClassLoader();
    }

    private static Class<?> loadFirst(String... names) throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                last = e;
            }
        }
        throw last != null ? last : new ClassNotFoundException("no class aliases supplied");
    }

    /** Backing handler for a raw {@code CustomPacketPayload} proxy. */
    private static final class RawPayloadHandler implements InvocationHandler {
        final Object type;
        final byte[] data;
        RawPayloadHandler(Object type, byte[] data) { this.type = type; this.data = data; }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getParameterCount() == 0 && method.getReturnType().isInstance(type)) {
                return type;
            }
            switch (method.getName()) {
                case "type":     return type;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals":   return proxy == (args != null ? args[0] : null);
                case "toString": return "RetromodRawPayload";
                default:
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                    return null;
            }
        }
    }

    /** Backing handler for the raw-bytes {@code StreamCodec} proxy, bound to one channel Type. */
    private static final class RawCodecHandler implements InvocationHandler {
        final Object type;
        RawCodecHandler(Object type) { this.type = type; }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 1 && method.getReturnType() != Void.TYPE) {
                byte[] data = drainBytes(args[0]);
                return newRawPayload(type, data);
            }
            if (args != null && args.length == 2 && method.getReturnType() == Void.TYPE) {
                byte[] data = bytesOf(args[1]);
                if (data != null) mBufWriteBytes.invoke(args[0], (Object) data);
                return null;
            }
            switch (method.getName()) {
                case "decode": {           // V decode(B buffer)
                    Object buf = args[0];
                    byte[] data = drainBytes(buf);
                    return newRawPayload(type, data);
                }
                case "encode": {           // void encode(B buffer, V value)
                    Object buf = args[0];
                    Object payload = args[1];
                    byte[] data = bytesOf(payload);
                    if (data != null) mBufWriteBytes.invoke(buf, (Object) data);
                    return null;
                }
                case "hashCode": return System.identityHashCode(proxy);
                case "equals":   return proxy == (args != null ? args[0] : null);
                case "toString": return "RetromodRawCodec";
                default:
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                    return null;
            }
        }
    }

    /** Backing handler for the new-API {@code PlayPayloadHandler} proxy. */
    private static final class ReceiveAdapter implements InvocationHandler {
        final Object oldHandler;
        Method oldReceive; // cached SAM (receive, 4 params)
        ReceiveAdapter(Object oldHandler) { this.oldHandler = oldHandler; }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!"receive".equals(method.getName())) {
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                if ("toString".equals(method.getName())) return "RetromodReceiveAdapter";
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == (args != null ? args[0] : null);
                return null;
            }
            try {
                Object payload = args[0];
                Object ctx = args[1];
                byte[] data = bytesOf(payload);
                if (data == null) data = new byte[0];

                Object nettyBuf = mUnpooledWrapped.invoke(null, (Object) data);
                Object friendlyBuf = friendlyBufCtor.newInstance(nettyBuf);

                Object mc = mMcGetInstance.invoke(null);
                Object connection = null;
                if (mMcGetConnection != null) {
                    try { connection = mMcGetConnection.invoke(mc); } catch (Throwable ignored) {}
                }
                Object sender = mCtxResponseSender.invoke(ctx);

                if (oldReceive == null) {
                    // A lambda is a hidden class with no reflectable methods, so resolve the SAM
                    // off the implemented interface; it dispatches virtually to the lambda body.
                    oldReceive = receiveFromInterfaces(oldHandler.getClass());
                    if (oldReceive == null) {
                        oldReceive = firstByNameAndArity(oldHandler.getClass(), "receive", 4);
                    }
                    if (oldReceive != null) {
                        try { oldReceive.setAccessible(true); } catch (Throwable ignored) {}
                    }
                }
                if (oldReceive != null) {
                    oldReceive.invoke(oldHandler, mc, connection, friendlyBuf, sender);
                }
            } catch (Throwable t) {
                warn("inbound packet dispatch failed", t);
            }
            return null;
        }
    }

    /** Read and consume all readable bytes from a FriendlyByteBuf / ByteBuf. */
    private static byte[] drainBytes(Object buf) {
        try {
            int n = (Integer) mBufReadableBytes.invoke(buf);
            byte[] data = new byte[Math.max(0, n)];
            if (n > 0) mBufReadBytes.invoke(buf, (Object) data);
            return data;
        } catch (Throwable t) {
            warn("buffer read failed", t);
            return new byte[0];
        }
    }

    /** Raw bytes carried by one of our payload proxies, or null if not ours. */
    private static byte[] bytesOf(Object payload) {
        try {
            if (payload != null && Proxy.isProxyClass(payload.getClass())) {
                InvocationHandler h = Proxy.getInvocationHandler(payload);
                if (h instanceof RawPayloadHandler) return ((RawPayloadHandler) h).data;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Constructor<?> singleArgCtor(Class<?> c) {
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 1) { ctor.setAccessible(true); return ctor; }
        }
        return c.getDeclaredConstructors()[0];
    }

    /** Find {@code receive(...4 args)} on an interface the handler implements (invokable even for
     *  a hidden-class lambda, since the Method is declared on the public interface). */
    private static Method receiveFromInterfaces(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Class<?> itf : k.getInterfaces()) {
                for (Method m : itf.getMethods()) {
                    if (m.getName().equals("receive") && m.getParameterCount() == 4) return m;
                }
                Method nested = receiveFromInterfaces(itf);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static Method firstByNameAndArity(Class<?> c, String name, int arity) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity) return m;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity) return m;
        }
        return null;
    }

    private static Method firstStaticByNameAndArity(Class<?> c, String name, int arity) {
        for (Method m : c.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                    && m.getName().equals(name) && m.getParameterCount() == arity) return m;
        }
        return null;
    }

    private static Method firstStaticByNameAndArityOrNull(Class<?> c, String name, int arity) {
        return firstStaticByNameAndArity(c, name, arity);
    }

    private static Method firstStaticByNames(Class<?> c, String... names) {
        for (String name : names) {
            Method found = firstStaticByNameAndArity(c, name, 0);
            if (found != null) return found;
        }
        return null;
    }

    private static Method noArgReturning(Class<?> c, Class<?> returnType) {
        for (Method m : c.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == returnType) return m;
        }
        return null;
    }

    private static Method staticNoArgReturning(Class<?> c, Class<?> returnType) {
        for (Method m : c.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                    && m.getReturnType() == returnType) return m;
        }
        return null;
    }

    /** old {@code getSendable() : Set} (channels we've registered for sending). */
    public static Set<Object> getSendable() {
        return Collections.emptySet();
    }
}
