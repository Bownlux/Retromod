/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.compat;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import com.retromod.util.RetromodLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reports a faked dependency version, and Fabric API under its old fabric id, to mods that check in code.
 */
public final class VersionSpoofer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-VersionSpoofer");

    private static final String SPOOF_RESOURCE = "/retromod/version-spoofs.json";

    /** Default: ON. Disable via {@code -Dretromod.spoofVersions=false} for debugging. */
    private static final boolean SPOOF_ENABLED =
            Boolean.parseBoolean(System.getProperty("retromod.spoofVersions", "true"));

    /** {@code modId -> spoofedVersionString}, loaded lazily on first use. */
    private static volatile Map<String, String> spoofTable;

    private static final java.util.concurrent.atomic.AtomicInteger spoofsApplied =
            new java.util.concurrent.atomic.AtomicInteger();

    // Fabric API for 26.x no longer provides the fabric id that older builds provided for it.
    private static final String FABRIC_ID = "fabric";
    private static final String FABRIC_API_ID = "fabric-api";

    // Some mods ask isModLoaded every frame, so the method is looked up once per loader class.
    private static final ClassValue<Optional<Method>> IS_MOD_LOADED = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> loaderClass) {
            try {
                return Optional.of(loaderClass.getMethod("isModLoaded", String.class));
            } catch (NoSuchMethodException | SecurityException e) {
                return Optional.empty();
            }
        }
    };

    private VersionSpoofer() {}

    /**
     * Replacement for {@code FabricLoader.isModLoaded(String)}, called from transformed mod bytecode.
     * It answers {@code fabric} for Fabric API when the host no longer provides that id.
     */
    public static boolean isModLoaded(Object fabricLoader, String modId) {
        if (askIsModLoaded(fabricLoader, modId)) return true;
        // only a "no" for the fabric id falls back, so a host that still provides it answers itself
        return FABRIC_ID.equals(modId) && askIsModLoaded(fabricLoader, FABRIC_API_ID);
    }

    /** The receiver's own answer, or false when it cannot be asked, as Fabric answers for a missing mod. */
    private static boolean askIsModLoaded(Object fabricLoader, String modId) {
        try {
            Optional<Method> method = IS_MOD_LOADED.get(fabricLoader.getClass());
            return method.isPresent() && Boolean.TRUE.equals(method.get().invoke(fabricLoader, modId));
        } catch (Throwable t) {
            LOGGER.debug("isModLoaded passthrough failed for {}: {}", modId, t.getMessage());
            return false;
        }
    }

    /**
     * Replacement for {@code FabricLoader.getModContainer(String)}, called from
     * transformed mod bytecode. {@code fabricLoader} is the receiver (a Fabric
     * {@code FabricLoader} instance), typed {@code Object} to build without a Fabric dep.
     *
     * @return real or proxy-wrapped {@code Optional<ModContainer>}
     */
    public static Optional<?> getModContainer(Object fabricLoader, String modId) {
        // empty on reflection failure matches Fabric's behaviour for a missing mod
        Optional<?> real;
        try {
            real = lookUpContainer(fabricLoader, modId);
            // on older hosts the fabric id named the Fabric API container, so the spoof rule below applies to it
            if (real.isEmpty() && FABRIC_ID.equals(modId)) real = lookUpContainer(fabricLoader, FABRIC_API_ID);
        } catch (Throwable t) {
            LOGGER.debug("Spoofer passthrough failed for {}: {}", modId, t.getMessage());
            return Optional.empty();
        }

        if (!SPOOF_ENABLED) return real;

        String spoofedVersion = getSpoofTable().get(modId);
        if (spoofedVersion == null) return real;
        if (!real.isPresent()) return real;               // mod not installed

        try {
            Object wrapped = wrapContainer(real.get(), spoofedVersion);
            spoofsApplied.incrementAndGet();
            return Optional.of(wrapped);
        } catch (Throwable t) {
            // best-effort: fall back to the real container if the proxy fails
            LOGGER.debug("Could not build spoof proxy for {} ({}): {}",
                    modId, spoofedVersion, t.getMessage());
            return real;
        }
    }

    /** The receiver's own {@code getModContainer(modId)} answer. */
    private static Optional<?> lookUpContainer(Object fabricLoader, String modId) throws ReflectiveOperationException {
        Method m = LegacyPublicMethods.method(fabricLoader.getClass(), "getModContainer", String.class);
        Object result = m.invoke(fabricLoader, modId);
        return (result instanceof Optional<?>) ? (Optional<?>) result : Optional.empty();
    }

    /** Wrap a {@code ModContainer} so {@code getMetadata()} returns a spoofing proxy. */
    private static Object wrapContainer(Object realContainer, String spoofedVersion)
            throws ClassNotFoundException {
        Class<?> containerIface = Class.forName("net.fabricmc.loader.api.ModContainer");
        return Proxy.newProxyInstance(
                containerIface.getClassLoader(),
                new Class<?>[]{containerIface},
                new ContainerHandler(realContainer, spoofedVersion));
    }

    /** Wrap a {@code ModMetadata} so {@code getVersion()} returns a spoofing proxy; other accessors pass through. */
    private static Object wrapMetadata(Object realMetadata, String spoofedVersion)
            throws ClassNotFoundException {
        Class<?> metaIface = Class.forName("net.fabricmc.loader.api.metadata.ModMetadata");
        return Proxy.newProxyInstance(
                metaIface.getClassLoader(),
                new Class<?>[]{metaIface},
                new MetadataHandler(realMetadata, spoofedVersion));
    }

    /**
     * Wrap a {@code Version} so {@code getFriendlyString()}/{@code toString()} return the
     * spoofed string and {@code compareTo} reports 0, letting SemVer range checks accept us.
     */
    private static Object wrapVersion(Object realVersion, String spoofedVersion)
            throws ClassNotFoundException {
        Class<?> versionIface = Class.forName("net.fabricmc.loader.api.Version");
        return Proxy.newProxyInstance(
                versionIface.getClassLoader(),
                new Class<?>[]{versionIface},
                new VersionHandler(realVersion, spoofedVersion));
    }

    private static final class ContainerHandler implements InvocationHandler {
        private final Object real;
        private final String spoofVersion;
        ContainerHandler(Object real, String spoofVersion) {
            this.real = real; this.spoofVersion = spoofVersion;
        }
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getMetadata".equals(method.getName()) && method.getParameterCount() == 0) {
                try {
                    Object meta = method.invoke(real);
                    return meta == null ? null : wrapMetadata(meta, spoofVersion);
                } catch (Throwable t) {
                    return method.invoke(real, args);
                }
            }
            return method.invoke(real, args);
        }
    }

    private static final class MetadataHandler implements InvocationHandler {
        private final Object real;
        private final String spoofVersion;
        MetadataHandler(Object real, String spoofVersion) {
            this.real = real; this.spoofVersion = spoofVersion;
        }
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getVersion".equals(method.getName()) && method.getParameterCount() == 0) {
                try {
                    Object ver = method.invoke(real);
                    return ver == null ? null : wrapVersion(ver, spoofVersion);
                } catch (Throwable t) {
                    return method.invoke(real, args);
                }
            }
            return method.invoke(real, args);
        }
    }

    private static final class VersionHandler implements InvocationHandler {
        private final Object real;
        private final String spoofVersion;
        VersionHandler(Object real, String spoofVersion) {
            this.real = real; this.spoofVersion = spoofVersion;
        }
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (("getFriendlyString".equals(name) || "toString".equals(name))
                    && method.getParameterCount() == 0) {
                return spoofVersion;
            }
            // report equal so SemVer range checks built on compareTo accept us
            if ("compareTo".equals(name) && method.getParameterCount() == 1) {
                return 0;
            }
            return method.invoke(real, args);
        }
    }

    /** Lazy-load the spoof table from the bundled JSON; a missing/malformed resource leaves it empty. */
    private static Map<String, String> getSpoofTable() {
        Map<String, String> local = spoofTable;
        if (local != null) return local;
        synchronized (VersionSpoofer.class) {
            if (spoofTable != null) return spoofTable;
            spoofTable = Collections.unmodifiableMap(loadTable());
            LOGGER.debug(RetromodLog.DETAIL, "VersionSpoofer loaded {} spoof rule(s) from {}",
                    spoofTable.size(), SPOOF_RESOURCE);
            return spoofTable;
        }
    }

    private static Map<String, String> loadTable() {
        Map<String, String> out = new HashMap<>();
        try (InputStream in = VersionSpoofer.class.getResourceAsStream(SPOOF_RESOURCE)) {
            if (in == null) {
                LOGGER.warn("{} not found - spoofer will passthrough every call", SPOOF_RESOURCE);
                return out;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                // Schema: { "spoofs": { "cloth-config": "13.999.999", ... } }
                if (root.has("spoofs")) {
                    JsonObject spoofs = root.getAsJsonObject("spoofs");
                    for (Map.Entry<String, JsonElement> e : spoofs.entrySet()) {
                        out.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not load spoof table: {}", e.getMessage());
        }
        return out;
    }

    /** Number of spoofed containers returned so far (diagnostics). */
    public static int getSpoofsApplied() {
        return spoofsApplied.get();
    }

    /** Test hook: drop the cached table so a test can re-populate it. */
    static void resetForTesting() {
        spoofTable = null;
        spoofsApplied.set(0);
    }

    /** Test hook: install a rule map directly, bypassing the JSON load. */
    static void setSpoofTableForTesting(Map<String, String> table) {
        spoofTable = Collections.unmodifiableMap(new HashMap<>(table));
    }
}
