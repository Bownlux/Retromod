/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.embedder.ModVersionInfo;
import com.retromod.polyfill.PolyfillProvider;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.forge.ForgeNeoForgeSynthetics;
import com.retromod.util.McReflect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** The CLI and AOT give every redirect the target a running game gives it. */
class OfflineRegistrationPathParityTest {

    private static final List<String> HOSTS = List.of("1.21.1", "1.21.11", "26.1", "26.2", "26.3");
    private static final String FIXTURE = "fixture/Empty";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedHost;
    private String savedCliHost;

    @TempDir
    Path directory;

    @BeforeEach
    void saveHost() throws Exception {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        savedCliHost = (String) cliField("TARGET_MC_VERSION").get(null);
        // The CLI builds its registry before any registration, as its main method does.
        cliRegistry();
    }

    @AfterEach
    void restoreHost() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = savedHost;
        cliField("TARGET_MC_VERSION").set(null, savedCliHost);
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(false);
        transformer.clearRedirectsForTesting();
    }

    /** A mod's loader, the host's loader, and the host version. */
    static Stream<Arguments> hosts() {
        List<Arguments> cases = new ArrayList<>();
        for (String host : HOSTS) {
            cases.add(Arguments.of("fabric", "fabric", host));
            cases.add(Arguments.of("forge", "forge", host));
            cases.add(Arguments.of("neoforge", "neoforge", host));
            cases.add(Arguments.of("forge", "neoforge", host));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} mod on {1} {2}")
    @MethodSource("hosts")
    void everyPathGivesEachRedirectTheGamesTarget(String modLoader, String hostLoader, String host)
            throws Exception {
        onHost(hostLoader, host);
        ModVersionInfo info = unknownSource(modLoader);
        Set<String> byDesign = offlineOnlyByDesign(modLoader, hostLoader, host);
        Set<String> hostShips = shippedByTheRealHost(hostLoader, host);
        Map<String, String> game = gameRedirects(hostLoader, host);
        game.keySet().removeAll(hostShips);

        // The source is unknown, so every path takes every shim the game takes.
        Map<String, Map<String, String>> offline = new TreeMap<>();
        offline.put("transform", afterReset(() -> cliCall("registerForTarget", info)));
        offline.put("batch", afterReset(() -> cliCall("registerAuxiliaryRedirects", info)));
        if (modLoader.equals(hostLoader)) {
            offline.put("aot", aotRedirects(modLoader, host));
        }

        List<String> failures = new ArrayList<>();
        for (var path : offline.entrySet()) {
            String label = path.getKey() + " for a " + modLoader + " mod on " + hostLoader + " " + host;
            List<String> conflicting = conflicts(game, path.getValue());
            if (!conflicting.isEmpty()) failures.add(label + " gives other targets: " + conflicting);
            if (sameScope(modLoader, hostLoader, host)) {
                Map<String, String> compared = new TreeMap<>(path.getValue());
                compared.keySet().removeIf(key -> byDesign.contains(key) && !game.containsKey(key));
                List<String> different = difference(game, compared);
                if (!different.isEmpty()) failures.add(label + " registers other entries: " + different);
            }
        }
        assertEquals(List.of(), failures, "every offline path must register what the game registers");
    }

    @ParameterizedTest(name = "{0} mod from 1.20.1 on {1} {2}")
    @MethodSource("hosts")
    void theOfflinePathsAgreeForAKnownSource(String modLoader, String hostLoader, String host)
            throws Exception {
        onHost(hostLoader, host);
        ModVersionInfo info = new ModVersionInfo("fixture", "1.0", "1.20.1", modLoader, "1",
                Set.of(), Set.of(), false);

        Map<String, String> transform = afterReset(() -> cliCall("registerForTarget", info));
        Map<String, String> batch = afterReset(() -> cliCall("registerAuxiliaryRedirects", info));

        assertTrue(transform.size() > 0, "the chain must register something");
        assertEquals(List.of(), difference(transform, batch),
                "transform and batch must register the same redirects");
    }

    // ---- the paths ----

    /** What the loader's entry point registers before it transforms a mod, then one transform. */
    private Map<String, String> gameRedirects(String hostLoader, String host) throws Exception {
        transformer.clearRedirectsForTesting();
        switch (hostLoader) {
            case "fabric" -> RetromodPreLaunch.registerShimsForTransform(host);
            case "forge" -> RetromodForge.registerTransformRedirects(transformer);
            default -> RetromodNeoForge.registerTransformRedirects(transformer);
        }
        // AOT's table is read after it transformed its jar, so this one is too.
        transformer.transformClass(emptyClass(), FIXTURE);
        return redirects();
    }

    private Map<String, String> afterReset(ThrowingRunnable registration) throws Exception {
        transformer.resetOfflineRegistrations();
        registration.run();
        transformer.transformClass(emptyClass(), FIXTURE);
        return redirects();
    }

    /** {@code RetromodCli.<name>(transformer, info, every shim an unknown source takes)}. */
    private void cliCall(String name, ModVersionInfo info) throws Exception {
        Class<?> cli = Class.forName("com.retromod.cli.RetromodCli");
        Method shims = cli.getDeclaredMethod("unknownSourceShims", String.class);
        shims.setAccessible(true);
        Object versionShims = shims.invoke(null, info.modLoaderType());
        Method register = cli.getDeclaredMethod(name, RetromodTransformer.class,
                ModVersionInfo.class, List.class);
        register.setAccessible(true);
        register.invoke(null, transformer, info, versionShims);
    }

    /** The redirects an offline AOT compile of an unknown-source mod leaves registered. */
    private Map<String, String> aotRedirects(String loader, String host) throws Exception {
        // No version in the file name, which the detector would read as the source.
        Path jar = Files.createTempDirectory(directory, "aot").resolve("parity-fixture.jar");
        writeUnknownSourceMod(jar, loader);
        Class<?> aot = Class.forName("com.retromod.aot.AotCompiler");
        Method offline = aot.getDeclaredMethod("forOfflineInputs", ShimRegistry.class,
                String.class, Path.class);
        offline.setAccessible(true);
        Object compiler = offline.invoke(null, cliRegistry(), host,
                directory.toRealPath().resolve("cache-" + loader + "-" + host));
        transformer.clearRedirectsForTesting();
        aot.getMethod("compileModAot", Path.class, boolean.class).invoke(compiler, jar, true);
        return redirects();
    }

    // ---- setup and comparison ----
    /**
     * The NeoForge stand-ins the game here registers only because this JVM lacks NeoForge: a real
     * host before 1.21.11 ships those classes, so its entry point skips them, as offline does.
     */
    private Set<String> shippedByTheRealHost(String hostLoader, String host) {
        if (!hostLoader.equals("neoforge")) return Set.of();
        transformer.clearRedirectsForTesting();
        ForgeNeoForgeSynthetics.registerAll(transformer, "26.1");
        Set<String> keys = new java.util.HashSet<>(redirects().keySet());
        transformer.clearRedirectsForTesting();
        ForgeNeoForgeSynthetics.registerAll(transformer, host);
        keys.removeAll(redirects().keySet());
        transformer.clearRedirectsForTesting();
        return keys;
    }

    /**
     * What offline registers on purpose beyond a Forge or NeoForge game: the neoforge polyfill
     * category, which those entry points turn off, and for a Forge host on 26.x the stand-ins for
     * NeoForge classes, for a jar detected as Forge that later runs on NeoForge.
     */
    private Set<String> offlineOnlyByDesign(String modLoader, String hostLoader, String host) {
        transformer.clearRedirectsForTesting();
        if (hostLoader.equals("fabric")) return Set.of();
        for (PolyfillProvider provider : ServiceLoader.load(PolyfillProvider.class)) {
            if ("neoforge".equals(provider.getCategory())) provider.registerPolyfills(transformer);
        }
        if (hostLoader.equals("forge") && RetromodVersion.isUnobfuscatedTarget(host)) {
            ForgeNeoForgeSynthetics.registerAll(transformer, host);
        }
        Set<String> keys = new java.util.HashSet<>(redirects().keySet());
        transformer.clearRedirectsForTesting();
        return keys;
    }


    private static void onHost(String hostLoader, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        cliField("TARGET_MC_VERSION").set(null, host);
        McReflect.setForceForge("forge".equals(hostLoader));
        McReflect.setForceNeoForge("neoforge".equals(hostLoader));
    }

    /**
     * Where the offline path and the game take the same providers. A Fabric host before 26.1 also
     * runs bridges found from its own classes, and a NeoForge host runs NeoForge's shims for a
     * Forge mod too, so those cases compare only the redirects both register.
     */
    private static boolean sameScope(String modLoader, String hostLoader, String host) {
        if (!modLoader.equals(hostLoader)) return false;
        return !modLoader.equals("fabric") || RetromodVersion.isUnobfuscatedTarget(host);
    }

    /** Every registration table of the transformer: one entry per map key, set elements as present. */
    private Map<String, String> redirects() {
        Map<String, String> table = new TreeMap<>();
        for (String line : transformer.registrationLines()) {
            if (!line.startsWith("transformer.")) continue;
            String[] parts = line.substring("transformer.".length()).split("\t", 3);
            if (parts.length == 3) {
                table.put(parts[0] + "\t" + parts[1], parts[2]);
            } else {
                table.put(String.join("\t", parts), "present");
            }
        }
        return table;
    }

    /** Each key both tables hold with a different target, game target first. */
    private static List<String> conflicts(Map<String, String> game, Map<String, String> offline) {
        List<String> differences = new ArrayList<>();
        for (var entry : game.entrySet()) {
            String other = offline.get(entry.getKey());
            if (other != null && !other.equals(entry.getValue())) {
                differences.add(entry.getKey() + "\n    game:    " + entry.getValue()
                        + "\n    offline: " + other);
            }
        }
        return differences;
    }

    /** Each key only one table holds, then each key they hold with different targets. */
    private static List<String> difference(Map<String, String> left, Map<String, String> right) {
        List<String> differences = new ArrayList<>();
        for (String key : left.keySet()) {
            if (!right.containsKey(key)) differences.add("only first: " + key);
        }
        for (String key : right.keySet()) {
            if (!left.containsKey(key)) differences.add("only second: " + key);
        }
        differences.addAll(conflicts(left, right));
        return differences;
    }

    private static ModVersionInfo unknownSource(String loader) {
        return new ModVersionInfo("fixture", "1.0", null, loader, "1", Set.of(), Set.of(), false);
    }

    private static ShimRegistry cliRegistry() throws Exception {
        Class<?> cli = Class.forName("com.retromod.cli.RetromodCli");
        Method shims = cli.getDeclaredMethod("unknownSourceShims", String.class);
        shims.setAccessible(true);
        shims.invoke(null, "fabric");
        return (ShimRegistry) cliField("shimRegistry").get(null);
    }

    private static Field cliField(String name) throws Exception {
        Field field = Class.forName("com.retromod.cli.RetromodCli").getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** A one-class mod whose metadata names its loader but no Minecraft version. */
    private static void writeUnknownSourceMod(Path jar, String loader) throws Exception {
        String path;
        String metadata;
        if (loader.equals("fabric")) {
            path = "fabric.mod.json";
            metadata = "{\"schemaVersion\":1,\"id\":\"parity_fixture\",\"version\":\"1.0.0\"}";
        } else {
            path = loader.equals("forge") ? "META-INF/mods.toml" : "META-INF/neoforge.mods.toml";
            metadata = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                    + "[[mods]]\nmodId=\"parity_fixture\"\nversion=\"1.0.0\"\n";
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(path));
            out.write(metadata.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(FIXTURE + ".class"));
            out.write(emptyClass());
            out.closeEntry();
        }
    }

    private static byte[] emptyClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, FIXTURE, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
