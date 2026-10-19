/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.boot;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/** Runs Retromod's discovery pass from the runtime jar while the loader is still listing mod files. */
public final class BootEngine {

    /** The engine entry inside the extracted runtime jar. */
    static final String ENGINE_CLASS = "com.retromod.inplace.DiscoveryEngine";

    /** Must match {@code DiscoveryEngine.PROTOCOL}. */
    public static final int PROTOCOL = 1;

    private BootEngine() {}

    /** Calls the discovery pass and returns its result map, or empty when it could not run. */
    public static Optional<Map<String, Object>> run(Path runtimeJar, Map<String, Object> request,
            BootLog log) {
        try {
            return run(new URL[] {runtimeJar.toUri().toURL()}, request, log);
        } catch (java.net.MalformedURLException e) {
            log.error("Retromod's in-place pass could not start: " + e);
            return Optional.empty();
        }
    }

    /** {@link #run(Path, Map, BootLog)} over an explicit class path, which tests use. */
    static Optional<Map<String, Object>> run(URL[] classPath, Map<String, Object> request,
            BootLog log) {
        // Only JDK types cross the call, so the engine sends its log lines back through a plain
        // BiConsumer of level and message.
        Map<String, Object> call = new HashMap<>(request);
        call.put("protocol", PROTOCOL);
        call.put("log", (BiConsumer<String, String>) (level, message) -> {
            switch (level) {
                case "error" -> log.error(message);
                case "warn" -> log.warn(message);
                default -> log.info(message);
            }
        });
        // The pass finishes and its class loader is closed before this method returns.
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (EngineClassLoader engine = new EngineClassLoader(classPath,
                BootEngine.class.getClassLoader())) {
            // Code that looks up services through the context class loader finds Retromod's, not
            // the host's, for the duration of the call.
            thread.setContextClassLoader(engine);
            Object result = Class.forName(ENGINE_CLASS, true, engine)
                    .getMethod("run", Map.class).invoke(null, call);
            // Copy the result with plain String keys.
            if (!(result instanceof Map<?, ?> map)) return Optional.empty();
            Map<String, Object> copy = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return Optional.of(copy);
        } catch (Throwable failure) {
            log.error("Retromod's in-place pass could not start: "
                    + LoaderHooks.unwrap(failure) + ". Old mods load as if Retromod's "
                    + "in-place mode were off. Please report this with logs/latest.log.");
            return Optional.empty();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** The common request entries every loader generation fills in. */
    public static Map<String, Object> request(Path gameDir, Path modsDir, String loader,
            String minecraft, String loaderBuild, String hostToml, String serveStyle) {
        Map<String, Object> request = new HashMap<>();
        request.put("gameDir", gameDir);
        request.put("modsDir", modsDir);
        request.put("modsListing", listMods(modsDir));
        request.put("loader", loader);
        request.put("minecraft", minecraft);
        request.put("loaderBuild", loaderBuild == null ? "" : loaderBuild);
        request.put("hostToml", hostToml);
        request.put("serveStyle", serveStyle);
        // A copy: the pass reads settings, it never sets them.
        Properties properties = new Properties();
        properties.putAll(System.getProperties());
        request.put("properties", properties);
        return request;
    }

    /** The mods folder exactly as the loader's own folder scan lists it. */
    static List<Path> listMods(Path modsDir) {
        List<Path> jars = new ArrayList<>();
        if (modsDir == null || !Files.isDirectory(modsDir)) return jars;
        // Loaders compare raw paths when they check what is already located, so this uses the
        // same listing call they do.
        try (Stream<Path> files = Files.list(modsDir)) {
            files.forEach(jars::add);
        } catch (IOException e) {
            // An unreadable folder has nothing to classify; the loader reports it.
        }
        return jars;
    }

    /** Reads a list of paths from a result map, ignoring anything else. */
    public static List<Path> paths(Map<String, Object> result, String key) {
        List<Path> paths = new ArrayList<>();
        if (result.get(key) instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Path path) paths.add(path);
            }
        }
        return paths;
    }

    /** The private loader the engine runs in. */
    static final class EngineClassLoader extends URLClassLoader {

        static {
            registerAsParallelCapable();
        }

        // Packages that belong to the game or a loader are never defined here. AWT and Swing are
        // refused too, so a stray dialog cannot open before the game window.
        private static final String[] REFUSED = {
                "net.minecraft.", "com.mojang.", "net.minecraftforge.", "net.neoforged.",
                "cpw.mods.", "net.fabricmc.", "org.quiltmc.", "org.spongepowered.",
                "java.awt.", "javax.swing.", "sun.awt.", "sun.lwawt."};

        private final ClassLoader asmSource;

        EngineClassLoader(URL[] classPath, ClassLoader asmSource) {
            // The parent is the JDK's platform loader, which can never define a Minecraft, loader,
            // or mod class. So nothing Retromod touches here lands in a parent of the game class
            // loader, where NeoForge 21.9 and newer would reject it (ClassLoadingGuardian). A probe
            // for a game class fails, and Retromod's frame writers fall back to their byte-based
            // path.
            super("retromod-engine", classPath, ClassLoader.getPlatformClassLoader());
            this.asmSource = asmSource;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String prefix : REFUSED) {
                if (name.startsWith(prefix)) {
                    throw new ClassNotFoundException(name + " is not available during "
                            + "Retromod's discovery pass");
                }
            }
            // ASM is the one library borrowed from the host.
            if (name.startsWith("org.objectweb.asm.")) {
                return loadAsm(name);
            }
            return super.loadClass(name, resolve);
        }

        /** ASM comes from the loader's own copy: the bootstrap's loader, then the system one. */
        private Class<?> loadAsm(String name) throws ClassNotFoundException {
            if (asmSource != null) {
                try {
                    return asmSource.loadClass(name);
                } catch (ClassNotFoundException ignored) {
                    // try the system loader next
                }
            }
            return ClassLoader.getSystemClassLoader().loadClass(name);
        }
    }
}
