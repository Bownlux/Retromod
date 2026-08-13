/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Adapts Forge 1.20.1's nested channel builder to Forge 1.20.6 through 1.21.x networking. */
public final class LegacyForgeChannelBuilder {

    private static final String BUILDER = "net.minecraftforge.network.ChannelBuilder";
    private static final String VERSION_TEST = "net.minecraftforge.network.Channel$VersionTest";
    private static final String LEGACY_MISSING_VERSION = "ABSENT \ud83e\udd14";
    private static final String LEGACY_VANILLA_VERSION = "ALLOWVANILLA \ud83d\udc93\ud83d\udc93\ud83d\udc93";

    private Object delegate;
    private String protocolVersion = "0";

    private LegacyForgeChannelBuilder(Object delegate) {
        this.delegate = delegate;
    }

    public static LegacyForgeChannelBuilder named(Object channelName) {
        try {
            Class<?> type = Class.forName(BUILDER, false,
                    LegacyForgeChannelBuilder.class.getClassLoader());
            Method named = findCompatibleMethod(type, "named", channelName);
            return new LegacyForgeChannelBuilder(named.invoke(null, channelName));
        } catch (ReflectiveOperationException e) {
            throw bridgeFailure("create channel builder", e);
        }
    }

    public LegacyForgeChannelBuilder networkProtocolVersion(Supplier<?> versionSupplier) {
        Object supplied = versionSupplier == null ? null : versionSupplier.get();
        protocolVersion = String.valueOf(supplied);
        int version = numericVersion(protocolVersion);
        invokeFluent("networkProtocolVersion", int.class, version);
        return this;
    }

    public LegacyForgeChannelBuilder clientAcceptedVersions(Predicate<String> predicate) {
        invokeVersionTest("clientAcceptedVersions", predicate);
        return this;
    }

    public LegacyForgeChannelBuilder serverAcceptedVersions(Predicate<String> predicate) {
        invokeVersionTest("serverAcceptedVersions", predicate);
        return this;
    }

    public Object eventNetworkChannel() {
        try {
            return delegate.getClass().getMethod("eventNetworkChannel").invoke(delegate);
        } catch (ReflectiveOperationException e) {
            throw bridgeFailure("create event network channel", e);
        }
    }

    public Object simpleChannel() {
        try {
            return delegate.getClass().getMethod("simpleChannel").invoke(delegate);
        } catch (ReflectiveOperationException e) {
            throw bridgeFailure("create simple network channel", e);
        }
    }

    private void invokeVersionTest(String methodName, Predicate<String> predicate) {
        try {
            ClassLoader loader = LegacyForgeChannelBuilder.class.getClassLoader();
            Class<?> testType = Class.forName(VERSION_TEST, false, loader);
            Object test = Proxy.newProxyInstance(loader, new Class<?>[]{testType},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "accepts" -> predicate == null
                                || predicate.test(legacyVersion(args, protocolVersion));
                        case "toString" -> "Retromod legacy network version test";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (args == null ? null : args[0]);
                        default -> throw new UnsupportedOperationException(method.toString());
                    });
            invokeFluent(methodName, testType, test);
        } catch (ReflectiveOperationException e) {
            throw bridgeFailure("adapt " + methodName, e);
        }
    }

    /** Converts the current status plus integer protocol back to the legacy predicate input. */
    static String legacyVersion(Object[] arguments, String advertisedVersion) {
        Object status = arguments != null && arguments.length > 0 ? arguments[0] : null;
        Object version = arguments != null && arguments.length > 1 ? arguments[1] : 0;
        String statusName = status instanceof Enum<?> value
                ? value.name() : String.valueOf(status);
        if ("MISSING".equals(statusName)) {
            return LEGACY_MISSING_VERSION;
        }
        if ("VANILLA".equals(statusName)) {
            return LEGACY_VANILLA_VERSION;
        }
        if (version instanceof Number number
                && number.intValue() == numericVersion(advertisedVersion)) {
            return advertisedVersion;
        }
        return String.valueOf(version);
    }

    private void invokeFluent(String name, Class<?> parameterType, Object value) {
        try {
            Object next = delegate.getClass().getMethod(name, parameterType).invoke(delegate, value);
            if (next != null) {
                delegate = next;
            }
        } catch (ReflectiveOperationException e) {
            throw bridgeFailure("call " + name, e);
        }
    }

    private static Method findCompatibleMethod(Class<?> owner, String name, Object argument)
            throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && (argument == null || method.getParameterTypes()[0].isInstance(argument))) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static int numericVersion(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value);
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return text.hashCode();
        }
    }

    private static IllegalStateException bridgeFailure(String action, ReflectiveOperationException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return new IllegalStateException("Retromod could not " + action
                + " through Forge's current networking API", cause);
    }
}
