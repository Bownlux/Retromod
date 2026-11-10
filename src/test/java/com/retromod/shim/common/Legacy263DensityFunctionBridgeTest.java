/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Old density functions, built and called through the old API, sample the same values on 26.3's compiled samplers. */
class Legacy263DensityFunctionBridgeTest {

    private static final String LEVELGEN = "net/minecraft/world/level/levelgen/";
    private static final String DF = "net.minecraft.world.level.levelgen.densityfunction.";

    @TempDir
    Path dir;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("26.3 compiling a tree of old and host functions samples what the old host computed")
    void hostCompilesOldTree() throws Throwable {
        Hosts hosts = hosts();
        List<Double> expected = grid(hosts.old, "tree", true);
        assertTrue(expected.stream().distinct().count() > 10, "the old tree varies: " + expected);
        assertGridEquals(expected, sampled(hosts.current, hosts.current.call("mod/Calls", "tree")), "26.3 compiling it");
        // The fake compiler prepares every cache it reaches; the one inside the old function must be among them.
        assertEquals(1, hosts.current.loadClass(DF + "DensityFunctionCompiler").getField("prepared").getInt(null),
                "the host's cache rule reached the child of the old function");
    }

    @Test
    @DisplayName("an old function that reads a noise is wired with the host's noise, as the old random state did")
    void oldFunctionReadsWiredNoise() throws Throwable {
        Hosts hosts = hosts();
        List<Double> expected = grid(hosts.old, "noisy", true);
        assertTrue(expected.stream().distinct().count() > 5, "the noise varies: " + expected);
        assertGridEquals(expected, sampled(hosts.current, hosts.current.call("mod/Calls", "noisy")), "26.3 wiring the noise");
    }

    @Test
    @DisplayName("mapAll swaps a marker under a reference in a host tree, as Lithostitched does")
    void mapAllSwapsMarkers() throws Throwable {
        Hosts hosts = hosts();
        List<Double> expected = grid(hosts.old, "swapped", true);
        Object swapped = hosts.current.call("mod/Calls", "swapped");
        assertGridEquals(expected, sampled(hosts.current, swapped), "26.3 sampling the swapped tree");
        assertGridEquals(expected, grid(hosts.current, "swapped", false), "the mod computing it");
    }

    @Test
    @DisplayName("the old compute and fillArray on a tree of host functions work outside world generation")
    void modComputesItself() throws Throwable {
        Hosts hosts = hosts();
        assertGridEquals(grid(hosts.old, "tree", true), grid(hosts.current, "tree", false), "compute");
        double[] old = (double[]) hosts.old.call("mod/Calls", "fill", hosts.old.call("mod/Calls", "tree"));
        double[] current = (double[]) hosts.current.call("mod/Calls", "fill", hosts.current.call("mod/Calls", "tree"));
        assertArrayEquals(old, current, 1e-5, "fillArray");
        // A noise has no world to seed it here, so the call names the problem instead of reading zero.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> hosts.current.call("mod/Calls", "sample", hosts.current.call("mod/Calls", "hostNoise"), 1, 2, 3));
        assertTrue(failure.getMessage().contains("outside world generation"), failure.getMessage());
    }

    @Test
    @DisplayName("TerraBlender's End lookup reads erosion from 26.3's bound climate sampler")
    void climateErosionReadsTheBoundSampler() throws Throwable {
        Hosts hosts = hosts();
        List<Double> old = new ArrayList<>();
        List<Double> current = new ArrayList<>();
        Object oldClimate = hosts.old.loadClass("net.minecraft.world.level.biome.Climate$Sampler")
                .getConstructor(hosts.old.loadClass("net.minecraft.world.level.levelgen.DensityFunction"))
                .newInstance(hosts.old.call("mod/Calls", "wave"));
        Object currentClimate = hosts.current.call("net/minecraft/world/level/biome/Climate", "boundTo",
                hosts.current.call("mod/Calls", "wave"));
        for (int x = -40; x <= 40; x += 8) {
            for (int z = -40; z <= 40; z += 8) {
                old.add((Double) hosts.old.call("mod/Calls", "endErosion", oldClimate, x, 64, z));
                current.add((Double) hosts.current.call("mod/Calls", "endErosion", currentClimate, x, 64, z));
            }
        }
        assertGridEquals(old, current, "erosion");
    }

    @Test
    @DisplayName("two mods' embedded copies share one tree: one mod's mapAll reaches through the other's function, and 26.3 samples both")
    void embeddedCopiesShareTrees() throws Throwable {
        Hosts hosts = hosts();
        Map<String, byte[]> host = compile(dir.resolve("embedded-host"), NEW_HOST);
        Map<String, byte[]> marker = embedded("lithostitched", hosts.translated);
        Map<String, byte[]> addon = embedded("addon", hosts.translated);
        assertTrue(marker.keySet().stream().anyMatch(n -> n.contains("/lithostitched") && n.endsWith("LegacyDensityFunctions")),
                "each jar carries its own copy");
        JarLoader game = new JarLoader(host, getClass().getClassLoader());
        JarLoader markerJar = new JarLoader(marker, game);
        JarLoader addonJar = new JarLoader(addon, game);
        Class<?> function = game.loadClass(DF + "DensityFunction");

        // The addon's function wraps a reference to the marker mod's placeholder.
        Object reference = call(markerJar, "markerReference");
        Object tree = addonJar.loadClass("mod.Scale").getConstructor(function, double.class).newInstance(reference, 3.0);
        Object replacement = call(markerJar, "wave");
        Object swapped = call(markerJar, "swapIn", tree, replacement);
        // The same tree built in one jar, for the expected values.
        Object local = call(markerJar, "swapIn", markerJar.loadClass("mod.Scale").getConstructor(function, double.class)
                .newInstance(call(markerJar, "markerReference"), 3.0), call(markerJar, "wave"));
        List<Double> expected = sampledWith(game, local);
        assertTrue(expected.stream().distinct().count() > 5, "the swapped tree varies: " + expected);
        assertGridEquals(expected, sampledWith(game, swapped), "the swap through the addon's function");
    }

    @Test
    @DisplayName("a function built for 26.2, which implements mapChildren, samples the same on 26.3")
    void builtFor262() throws Throwable {
        Map<String, String> oldSources = new HashMap<>(OLD_HOST_262);
        oldSources.putAll(MOD_262);
        Map<String, byte[]> compiled = compile(dir.resolve("old-262"), oldSources);
        Run old = new Run(compiled, Map.of());
        Map<String, byte[]> translated = transform("26.3", Legacy263DensityFunctionBridgeTest::register, only(compiled, "mod/"));
        Run current = new Run(compile(dir.resolve("new-host-262"), NEW_HOST), translated);
        List<Double> expected = grid(old, "tree", true);
        assertTrue(expected.stream().distinct().count() > 5, "the 26.2 tree varies: " + expected);
        assertGridEquals(expected, sampled(current, current.call("mod/Calls", "tree")), "26.3 compiling it");
        assertGridEquals(expected, grid(current, "tree", false), "the mod computing it");
        assertGridEquals(grid(old, "inverted", true), grid(current, "inverted", false), "the old invert");
    }

    @Test
    @DisplayName("a volume samples each host child once through its own volume path and reads it back at each point")
    void volumeReadsHostChildrenOnce() throws Throwable {
        Run run = hosts().current;
        Class<?> function = run.loadClass(DF + "DensityFunction");
        Class<?> factories = run.loadClass(DF + "DensityFunctions");
        Object counted = factories.getMethod("counted", function).invoke(null,
                factories.getMethod("constant", float.class).invoke(null, 1f));
        int[] size = {3, 2, 3};
        int[] origin = {10, 64, -5};

        // The volume path of the child adds 1000; a point read would not.
        List<Float> scaled = volume(run, run.loadClass("mod.Scale").getConstructor(function, double.class).newInstance(counted, 2.0),
                size, origin);
        assertEquals(1, counter(run, "volumes"), "the child was sampled over the volume once");
        assertEquals(0, counter(run, "points"), "no point of the child was sampled on its own");
        int i = 0;
        for (int z = 0; z < size[2]; z++) for (int x = 0; x < size[0]; x++) for (int y = 0; y < size[1]; y++) {
            assertEquals((float) (1001.0 * 2 + (origin[1] + y) * 0.001), scaled.get(i++), "at " + x + "," + y + "," + z);
        }

        // A read one block east stays in the volume except on its last column, which samples that point.
        List<Float> shifted = volume(run, run.loadClass("mod.Shift").getConstructor(function, int.class).newInstance(counted, 1),
                size, origin);
        i = 0;
        for (int z = 0; z < size[2]; z++) for (int x = 0; x < size[0]; x++) for (int y = 0; y < size[1]; y++) {
            assertEquals(x + 1 < size[0] ? 1001f : 1f, shifted.get(i++), "shifted read at " + x + "," + y + "," + z);
        }
        assertEquals(size[1] * size[2], counter(run, "points"), "only the reads outside the volume were points");
        assertEquals(0, run.loadClass(DF + "SamplerContext").getField("open").getInt(null), "every scratch buffer was released");
    }

    @Test
    @DisplayName("an old function reports the axes its own compute reads, with its children's")
    void domainAxesFromOwnReadsAndChildren() throws Throwable {
        Hosts hosts = hosts();
        assertEquals(2, readAxes(hosts.translated.get("mod/Scale")), "Scale reads y");
        assertEquals(5, readAxes(hosts.translated.get("mod/Wave")), "Wave reads x and z");
        assertEquals(7, readAxes(hosts.translated.get("mod/Shift")), "Shift reads all three");
        assertEquals(0, readAxes(hosts.translated.get("mod/Marker")), "Marker reads none");
        Run run = hosts.current;
        Class<?> function = run.loadClass(DF + "DensityFunction");
        Object constant = run.loadClass(DF + "DensityFunctions").getMethod("constant", float.class).invoke(null, 1f);
        Object overConstant = run.loadClass("mod.Scale").getConstructor(function, double.class).newInstance(constant, 2.0);
        Object overWave = run.loadClass("mod.Scale").getConstructor(function, double.class)
                .newInstance(run.loadClass("mod.Wave").getConstructor().newInstance(), 2.0);
        assertEquals(2, function.getMethod("domainAxes").invoke(overConstant), "y from Scale, none from a constant");
        assertEquals(7, function.getMethod("domainAxes").invoke(overWave), "y from Scale, x and z from Wave");
    }

    @Test
    @DisplayName("a subclass that reads y through a helper, and a child its mapAll never visits, keep the y axis")
    void hiddenReadsKeepTheYAxis() throws Throwable {
        Hosts hosts = hosts();
        Run run = hosts.current;
        Class<?> function = run.loadClass(DF + "DensityFunction");
        Object flat = run.loadClass(DF + "DensityFunctions").getMethod("xz").invoke(null);
        for (String probe : List.of("mod.SubY", "mod.Hidden")) {
            Object old = run.loadClass(probe).getConstructor(function).newInstance(flat);
            assertEquals(7, function.getMethod("domainAxes").invoke(old), probe + " reads y where its children cannot say");
            // The host samples it like 26.3, sliced to one height when it reports no y; the mod's compute is the truth.
            List<Double> host = sampled(run, old);
            List<Double> own = new ArrayList<>();
            for (int[] at : POSITIONS) own.add((Double) run.call("mod/Calls", "sample", old, at[0], at[1], at[2]));
            assertGridEquals(own, host, probe + " on the host");
        }
        assertEquals(7, readAxes(hosts.translated.get("mod/SubY")), "a compute that hands its context to a helper");
        assertEquals(0, readAxes(hosts.translated.get("mod/Base")), "Base reads only the child its mapAll visits");
        assertEquals(7, readAxes(hosts.translated.get("mod/Hidden")), "a static child mapAll never visits");
    }

    @Test
    @DisplayName("the old interpolated and cache factories, and the two-argument noise holder, reach 26.3's")
    void factoriesAndNoiseHolder() throws Throwable {
        Run run = hosts().current;
        Object constant = run.loadClass(DF + "DensityFunctions").getMethod("constant", float.class).invoke(null, 1f);
        Object cells = run.call("mod/Calls", "cells", constant);
        Class<?> interpolated = run.loadClass(DF + "DensityFunctions$Interpolated");
        assertEquals(interpolated, cells.getClass());
        assertEquals(4, interpolated.getMethod("cellXz").invoke(cells), "the overworld's horizontal cell");
        assertEquals(8, interpolated.getMethod("cellY").invoke(cells), "the overworld's vertical cell");
        assertEquals(run.loadClass(DF + "op.CacheFunction"), interpolated.getMethod("input").invoke(cells).getClass());
        // No old noise exists on 26.3, so the holder an old caller builds reads as unwired.
        Object holder = run.call("mod/Calls", "holderWith", (Object) null);
        assertEquals(0.0, (Double) run.call("mod/Calls", "sample", holder, 1, 2, 3), 1e-9);
    }

    @Test
    @DisplayName("an old function gets the codec bridge, and a simple one keeps its own defaults")
    void classShapes() throws Throwable {
        Map<String, byte[]> translated = hosts().translated;
        ClassNode scale = node(translated.get("mod/Scale"));
        assertEquals(List.of(LegacyKeyDispatchCodecBridge.DENSITY_FUNCTION_BRIDGE), scale.interfaces);
        assertTrue(scale.methods.stream().anyMatch(m -> m.name.equals("compute")
                && m.desc.equals("(L" + Legacy263DensityFunctionBridge.TYPE + "$FunctionContext;)D")));
        assertEquals(List.of(Legacy263DensityFunctionBridge.SIMPLE_BRIDGE), node(translated.get("mod/Wave")).interfaces);
        ClassNode bridge = node(RetromodTransformer.getInstance().getSyntheticClasses()
                .get(LegacyKeyDispatchCodecBridge.DENSITY_FUNCTION_BRIDGE));
        assertEquals(List.of(LEVELGEN + "densityfunction/DensityFunction", Legacy263DensityFunctionBridge.TYPE),
                bridge.interfaces);
        assertTrue(calls(translated.get("mod/Calls"), "endErosion")
                .contains(Legacy263DensityFunctionBridge.HELPER + ".erosion(Lnet/minecraft/world/level/biome/Climate$Sampler;)L"
                        + LEVELGEN + "densityfunction/DensityFunction;"));
    }

    @Test
    @DisplayName("the bridge stays off a host that still computes density functions, and needs 26.3 offline")
    void gate() {
        assertTrue(Legacy263DensityFunctionBridge.hostHasSamplers(new Object(), true), "26.3");
        assertFalse(Legacy263DensityFunctionBridge.hostHasSamplers(null, true), "26.2 has no samplers");
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(Legacy263DensityFunctionBridge.hostHasSamplers(null, false), "offline 26.2");
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(Legacy263DensityFunctionBridge.hostHasSamplers(null, false), "offline 26.3");
    }

    /** What the 26.3 shims register for density functions, in their order. */
    static void register(RetromodTransformer t) {
        t.registerClassRedirect(LEVELGEN + "DensityFunction", LEVELGEN + "densityfunction/DensityFunction");
        t.registerClassRedirect(LEVELGEN + "DensityFunctions", LEVELGEN + "densityfunction/DensityFunctions");
        t.registerClassRedirect(LEVELGEN + "DensityFunctions$HolderHolder",
                LEVELGEN + "densityfunction/DensityFunctions$HolderHolder");
        LegacyKeyDispatchCodecBridge.registerRemovedClass(t);
        LegacyKeyDispatchCodecBridge.registerAccessorBridges(t);
        Legacy263DensityFunctionBridge.register(t);
    }

    private record Hosts(Run old, Run current, Map<String, byte[]> translated) {}

    private Hosts hosts() throws Exception {
        Map<String, String> oldSources = new HashMap<>(OLD_HOST);
        oldSources.putAll(MOD);
        Map<String, byte[]> oldCompiled = compile(dir.resolve("old"), oldSources);
        Map<String, byte[]> oldMod = only(oldCompiled, "mod/");
        Run old = new Run(oldCompiled, Map.of());
        Map<String, byte[]> translated = transform("26.3", Legacy263DensityFunctionBridgeTest::register, oldMod);
        Run current = new Run(compile(dir.resolve("new-host"), NEW_HOST), translated);
        return new Hosts(old, current, translated);
    }

    /** The mod's function {@code name} at each grid position, computed by the mod; the old host wires it first. */
    private static List<Double> grid(Run run, String name, boolean wire) throws Throwable {
        Object function = run.call("mod/Calls", name);
        if (wire) function = run.call("net/minecraft/world/level/levelgen/RandomState", "wire", function);
        List<Double> values = new ArrayList<>();
        for (int[] at : POSITIONS) {
            values.add((Double) run.call("mod/Calls", "sample", function, at[0], at[1], at[2]));
        }
        return values;
    }

    /** {@code function} compiled by the 26.3 host and sampled at each grid position. */
    private static List<Double> sampled(Run run, Object function) throws Throwable {
        Object sampler = run.call("net/minecraft/world/level/levelgen/densityfunction/DensityFunctionCompiler",
                "compile", function);
        List<Double> values = new ArrayList<>();
        for (int[] at : POSITIONS) {
            values.add((double) (float) run.call("net/minecraft/world/level/levelgen/densityfunction/DensityFunctionCompiler",
                    "sample", sampler, at[0], at[1], at[2]));
        }
        return values;
    }

    /** {@code function} compiled and sampled by the 26.3 host in {@code game}. */
    private static List<Double> sampledWith(ClassLoader game, Object function) throws Throwable {
        Class<?> compiler = game.loadClass(DF + "DensityFunctionCompiler");
        Object sampler = compiler.getMethod("compile", game.loadClass(DF + "DensityFunction")).invoke(null, function);
        java.lang.reflect.Method sample = compiler.getMethod("sample", game.loadClass(DF + "DensitySampler"), int.class, int.class, int.class);
        List<Double> values = new ArrayList<>();
        for (int[] at : POSITIONS) values.add((double) (float) sample.invoke(null, sampler, at[0], at[1], at[2]));
        return values;
    }

    private static Object call(ClassLoader jar, String name, Object... args) throws Throwable {
        for (java.lang.reflect.Method method : jar.loadClass("mod.Calls").getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                try {
                    return method.invoke(null, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
        throw new AssertionError("no method mod.Calls." + name);
    }

    /** The translated classes embedded under {@code key}, as one jar the game loads. */
    private static Map<String, byte[]> embedded(String key, Map<String, byte[]> translated) throws Exception {
        java.io.ByteArrayOutputStream jar = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(jar)) {
            for (Map.Entry<String, byte[]> entry : translated.entrySet()) {
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        com.retromod.core.SyntheticEmbedder.ByteEmbeddingResult result = com.retromod.core.SyntheticEmbedder
                .embedIntoJarBytes(jar.toByteArray(), key, RetromodTransformer.getInstance());
        assertTrue(result.succeeded() && result.embeddedCount() > 0, key + " embeds the rebuilt classes");
        Map<String, byte[]> classes = new HashMap<>();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(result.jarBytes()))) {
            for (java.util.zip.ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                String name = entry.getName();
                if (name.endsWith(".class")) classes.put(name.substring(0, name.length() - ".class".length()), in.readAllBytes());
            }
        }
        return classes;
    }

    /** One jar's classes over a parent, which they win over. */
    private static final class JarLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        JarLoader(Map<String, byte[]> classes, ClassLoader parent) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) return super.loadClass(name, resolve);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** {@code function} compiled by the host and sampled over one volume, after the counters are reset. */
    private static List<Float> volume(Run run, Object function, int[] size, int[] origin) throws Throwable {
        Class<?> factories = run.loadClass(DF + "DensityFunctions");
        factories.getField("points").setInt(null, 0);
        factories.getField("volumes").setInt(null, 0);
        Object sampler = run.call("net/minecraft/world/level/levelgen/densityfunction/DensityFunctionCompiler", "compile", function);
        Class<?> volumeType = run.loadClass(DF + "DensityVolume");
        Object volume = volumeType.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class)
                .newInstance(size[0], size[1], size[2], origin[0], origin[1], origin[2]);
        int count = size[0] * size[1] * size[2];
        Class<?> bufferType = run.loadClass(DF + "DensityBuffer");
        Object buffer = bufferType.getConstructor(int.class).newInstance(count);
        Class<?> contextType = run.loadClass(DF + "SamplerContext");
        run.loadClass(DF + "DensitySampler").getMethod("sampleVolume", contextType, bufferType, volumeType)
                .invoke(sampler, contextType.getField("EMPTY_UNCACHED").get(null), buffer, volume);
        List<Float> values = new ArrayList<>();
        for (int i = 0; i < count; i++) values.add((Float) bufferType.getMethod("get", int.class).invoke(buffer, i));
        return values;
    }

    private static int counter(Run run, String name) throws Exception {
        return run.loadClass(DF + "DensityFunctions").getField(name).getInt(null);
    }

    /** The value {@code retromod$readAxes()} returns in a translated class, or -1 when the transform left it out. */
    private static int readAxes(byte[] bytes) {
        for (var method : node(bytes).methods) {
            if (method.name.equals("retromod$readAxes")) {
                return ((org.objectweb.asm.tree.IntInsnNode) method.instructions.getFirst()).operand;
            }
        }
        return -1;
    }

    private static void assertGridEquals(List<Double> expected, List<Double> actual, String what) {
        assertEquals(expected.size(), actual.size(), what);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), 1e-5, what + " at " + java.util.Arrays.toString(POSITIONS[i]));
        }
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static final int[][] POSITIONS = positions();

    private static int[][] positions() {
        List<int[]> positions = new ArrayList<>();
        for (int x = -12; x <= 12; x += 4) {
            for (int y = -8; y <= 72; y += 20) {
                for (int z = -12; z <= 12; z += 6) {
                    positions.add(new int[]{x, y, z});
                }
            }
        }
        return positions.toArray(new int[0][]);
    }

    /** Holders and a noise formula both hosts share. */
    private static final Map<String, String> COMMON = Map.of(
            "net/minecraft/core/Holder.java", """
                    package net.minecraft.core;
                    public interface Holder<T> {
                        T value();
                        static <T> Holder<T> direct(T value) { return () -> value; }
                    }""",
            "net/minecraft/world/level/levelgen/FakeNoise.java", """
                    package net.minecraft.world.level.levelgen;
                    public final class FakeNoise {
                        public static double at(double x, double y, double z) {
                            return Math.sin(x * 0.37 + y * 0.11) * Math.cos(z * 0.23) * 0.75;
                        }
                    }""");

    /** The old API: functions compute doubles, mapAll rebuilds trees, and the random state wires noises. */
    private static final Map<String, String> OLD_HOST = merge(COMMON, Map.of(
            "net/minecraft/util/KeyDispatchDataCodec.java",
            "package net.minecraft.util; public final class KeyDispatchDataCodec<A> {}",
            "net/minecraft/world/level/levelgen/synth/NormalNoise.java", """
                    package net.minecraft.world.level.levelgen.synth;
                    public final class NormalNoise {
                        public double getValue(double x, double y, double z) { return net.minecraft.world.level.levelgen.FakeNoise.at(x, y, z); }
                    }""",
            "net/minecraft/world/level/levelgen/DensityFunction.java", """
                    package net.minecraft.world.level.levelgen;
                    import net.minecraft.core.Holder;
                    import net.minecraft.util.KeyDispatchDataCodec;
                    import net.minecraft.world.level.levelgen.synth.NormalNoise;
                    public interface DensityFunction {
                        double compute(FunctionContext context);
                        void fillArray(double[] values, ContextProvider provider);
                        DensityFunction mapAll(Visitor visitor);
                        double minValue();
                        double maxValue();
                        KeyDispatchDataCodec<? extends DensityFunction> codec();
                        default DensityFunction clamp(double min, double max) { return DensityFunctions.clamp(this, min, max); }
                        interface FunctionContext { int blockX(); int blockY(); int blockZ(); }
                        interface ContextProvider {
                            FunctionContext forIndex(int index);
                            void fillAllDirectly(double[] values, DensityFunction function);
                        }
                        interface SimpleFunction extends DensityFunction {
                            default void fillArray(double[] values, ContextProvider provider) { provider.fillAllDirectly(values, this); }
                            default DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
                        }
                        record SinglePointContext(int blockX, int blockY, int blockZ) implements FunctionContext {}
                        interface Visitor {
                            DensityFunction apply(DensityFunction function);
                            default NoiseHolder visitNoise(NoiseHolder noise) { return noise; }
                        }
                        record NoiseHolder(Holder<Object> noiseData, NormalNoise noise) {
                            public NoiseHolder(Holder<Object> noiseData) { this(noiseData, null); }
                            public double getValue(double x, double y, double z) { return noise == null ? 0.0 : noise.getValue(x, y, z); }
                            public double maxValue() { return 2.0; }
                        }
                    }""",
            "net/minecraft/world/level/levelgen/DensityFunctions.java", """
                    package net.minecraft.world.level.levelgen;
                    import net.minecraft.core.Holder;
                    import net.minecraft.util.KeyDispatchDataCodec;
                    public final class DensityFunctions {
                        public static DensityFunction constant(double value) { return new Constant(value); }
                        public static DensityFunction add(DensityFunction a, DensityFunction b) { return new Add(a, b); }
                        public static DensityFunction cache(DensityFunction input) { return new Cache(input); }
                        public static DensityFunction noise(Holder<Object> noise) { return new Noise(new DensityFunction.NoiseHolder(noise)); }
                        static DensityFunction clamp(DensityFunction input, double min, double max) { return new Clamp(input, min, max); }
                        public static DensityFunction interpolated(DensityFunction input) { return new Cache(input); }
                        public static DensityFunction yClampedGradient(int fromY, int toY, double from, double to) { return constant(0); }
                        public static DensityFunction flatCache(DensityFunction input) { return new Cache(input); }
                        abstract static class Base implements DensityFunction {
                            public void fillArray(double[] values, ContextProvider provider) { provider.fillAllDirectly(values, this); }
                            public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
                        }
                        static final class Constant extends Base {
                            final double value;
                            Constant(double value) { this.value = value; }
                            public double compute(FunctionContext c) { return value; }
                            public DensityFunction mapAll(Visitor v) { return v.apply(this); }
                            public double minValue() { return value; }
                            public double maxValue() { return value; }
                        }
                        static final class Add extends Base {
                            final DensityFunction a, b;
                            Add(DensityFunction a, DensityFunction b) { this.a = a; this.b = b; }
                            public double compute(FunctionContext c) { return a.compute(c) + b.compute(c); }
                            public DensityFunction mapAll(Visitor v) { return v.apply(new Add(a.mapAll(v), b.mapAll(v))); }
                            public double minValue() { return a.minValue() + b.minValue(); }
                            public double maxValue() { return a.maxValue() + b.maxValue(); }
                        }
                        static final class Cache extends Base {
                            final DensityFunction input;
                            Cache(DensityFunction input) { this.input = input; }
                            public double compute(FunctionContext c) { return input.compute(c); }
                            public DensityFunction mapAll(Visitor v) { return v.apply(new Cache(input.mapAll(v))); }
                            public double minValue() { return input.minValue(); }
                            public double maxValue() { return input.maxValue(); }
                        }
                        static final class Clamp extends Base {
                            final DensityFunction input; final double min, max;
                            Clamp(DensityFunction input, double min, double max) { this.input = input; this.min = min; this.max = max; }
                            public double compute(FunctionContext c) { return Math.max(min, Math.min(max, input.compute(c))); }
                            public DensityFunction mapAll(Visitor v) { return v.apply(new Clamp(input.mapAll(v), min, max)); }
                            public double minValue() { return min; }
                            public double maxValue() { return max; }
                        }
                        static final class Noise extends Base {
                            final DensityFunction.NoiseHolder noise;
                            Noise(DensityFunction.NoiseHolder noise) { this.noise = noise; }
                            public double compute(FunctionContext c) { return noise.getValue(c.blockX(), c.blockY(), c.blockZ()); }
                            public DensityFunction mapAll(Visitor v) { return v.apply(new Noise(v.visitNoise(noise))); }
                            public double minValue() { return -noise.maxValue(); }
                            public double maxValue() { return noise.maxValue(); }
                        }
                        public record HolderHolder(Holder<DensityFunction> function) implements DensityFunction {
                            public double compute(FunctionContext c) { return function.value().compute(c); }
                            public void fillArray(double[] values, ContextProvider provider) { function.value().fillArray(values, provider); }
                            public DensityFunction mapAll(Visitor v) { return v.apply(new HolderHolder(Holder.direct(function.value().mapAll(v)))); }
                            public double minValue() { return function.value().minValue(); }
                            public double maxValue() { return function.value().maxValue(); }
                            public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
                        }
                    }""",
            "net/minecraft/world/level/levelgen/RandomState.java", """
                    package net.minecraft.world.level.levelgen;
                    import net.minecraft.world.level.levelgen.synth.NormalNoise;
                    public final class RandomState {
                        public static DensityFunction wire(DensityFunction function) {
                            return function.mapAll(new DensityFunction.Visitor() {
                                public DensityFunction apply(DensityFunction f) { return f; }
                                public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder n) {
                                    return new DensityFunction.NoiseHolder(n.noiseData(), new NormalNoise());
                                }
                            });
                        }
                    }""",
            "net/minecraft/world/level/biome/Climate.java", """
                    package net.minecraft.world.level.biome;
                    import net.minecraft.world.level.levelgen.DensityFunction;
                    public class Climate {
                        public static final class Sampler {
                            private final DensityFunction erosion;
                            public Sampler(DensityFunction erosion) { this.erosion = erosion; }
                            public DensityFunction erosion() { return erosion; }
                        }
                    }"""));

    /** 26.3's shape: functions compile into float samplers after the compiler rewrites their trees. */
    private static final Map<String, String> NEW_HOST = merge(merge(COMMON, Map.of(
            "net/minecraft/world/level/levelgen/densityfunction/ScopedDensityBuffer.java", """
                    package net.minecraft.world.level.levelgen.densityfunction;
                    public class ScopedDensityBuffer extends DensityBuffer implements AutoCloseable {
                        public ScopedDensityBuffer(int size) { super(size); }
                        public void close() { SamplerContext.open--; }
                    }""")), merge(Map.of(
            "com/mojang/serialization/Codec.java", """
                    package com.mojang.serialization;
                    import java.util.function.Function;
                    public interface Codec<A> {
                        default <S> Codec<S> xmap(Function<? super A, ? extends S> to, Function<? super S, ? extends A> from) { return new Codec<S>() {}; }
                    }""",
            "com/mojang/serialization/MapCodec.java", """
                    package com.mojang.serialization;
                    public abstract class MapCodec<A> { public static <A> MapCodec<A> unit(A value) { return null; } }""",
            "net/minecraft/util/Interval.java", """
                    package net.minecraft.util;
                    public final class Interval {
                        public static final Interval INFINITE = new Interval(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
                        private final float min, max;
                        private Interval(float min, float max) { this.min = min; this.max = max; }
                        public static Interval of(float min, float max) { return new Interval(min, max); }
                        public float min() { return min; }
                        public float max() { return max; }
                    }""",
            "net/minecraft/util/RandomSource.java", "package net.minecraft.util; public interface RandomSource {}",
            "net/minecraft/resources/Identifier.java", "package net.minecraft.resources; public final class Identifier {}",
            "net/minecraft/world/level/levelgen/blending/Blender.java", """
                    package net.minecraft.world.level.levelgen.blending;
                    public class Blender { public static Blender empty() { return new Blender(); } }""",
            "net/minecraft/world/level/levelgen/synth/Noise.java", """
                    package net.minecraft.world.level.levelgen.synth;
                    import net.minecraft.util.Interval;
                    public interface Noise { Interval range(); float get(double x, double y, double z); }""",
            "net/minecraft/world/level/levelgen/synth/NormalNoise.java", """
                    package net.minecraft.world.level.levelgen.synth;
                    import com.mojang.serialization.Codec;
                    import net.minecraft.core.Holder;
                    public final class NormalNoise { public static final Codec<Holder<NormalNoise>> CODEC = new Codec<>() {}; }""",
            "net/minecraft/world/level/biome/Climate.java", """
                    package net.minecraft.world.level.biome;
                    import net.minecraft.world.level.levelgen.densityfunction.*;
                    public class Climate {
                        public static Sampler boundTo(DensityFunction erosion) {
                            DensitySampler.Bound bound = new DensitySampler.Bound(DensityFunctionCompiler.compile(erosion), SamplerContext.EMPTY_UNCACHED);
                            return new Sampler(bound);
                        }
                        public static final class Sampler {
                            private final DensitySampler.Bound erosion;
                            public Sampler(DensitySampler.Bound erosion) { this.erosion = erosion; }
                            public DensitySampler.Bound temperature() { return erosion; }
                            public DensitySampler.Bound humidity() { return erosion; }
                            public DensitySampler.Bound continentalness() { return erosion; }
                            public DensitySampler.Bound erosion() { return erosion; }
                            public DensitySampler.Bound depth() { return erosion; }
                            public DensitySampler.Bound weirdness() { return erosion; }
                        }
                    }"""), Map.of(
            "net/minecraft/world/level/levelgen/densityfunction/DensityFunction.java", """
                    package net.minecraft.world.level.levelgen.densityfunction;
                    import com.mojang.serialization.MapCodec;
                    import net.minecraft.core.Holder;
                    import net.minecraft.util.Interval;
                    import net.minecraft.world.level.levelgen.synth.Noise;
                    import net.minecraft.world.level.levelgen.synth.NormalNoise;
                    public interface DensityFunction {
                        int ALL_AXES = 7;
                        DensitySampler compileSampler(CompileContext context);
                        DensityFunction rewriteChildren(DfRewriteRule rule);
                        Interval range();
                        int domainAxes();
                        MapCodec<? extends DensityFunction> codec();
                        default DensityFunction clamp(float min, float max) { return new DensityFunctions.Clamp(this, min, max); }
                        default DensityFunction reciprocal() { return new DensityFunctions.Reciprocal(this); }
                        interface CompileContext {
                            Noise createNoiseSampler(Holder<NormalNoise> noise);
                            net.minecraft.util.RandomSource createRandom(net.minecraft.resources.Identifier id);
                            net.minecraft.util.RandomSource createEndIslandRandom();
                        }
                    }""",
            "net/minecraft/world/level/levelgen/densityfunction/DensitySampler.java", """
                    package net.minecraft.world.level.levelgen.densityfunction;
                    public interface DensitySampler {
                        void sampleVolume(SamplerContext context, DensityBuffer buffer, DensityVolume volume);
                        static void sampleVolumeNaive(SamplerContext context, DensityBuffer buffer, DensityVolume volume, DensitySampler sampler) {
                            int i = 0;
                            for (int z = 0; z < volume.sizeZ(); z++) for (int x = 0; x < volume.sizeX(); x++) for (int y = 0; y < volume.sizeY(); y++)
                                buffer.set(i++, sampler.sampleValue(context, volume.blockX(x), volume.blockY(y), volume.blockZ(z)));
                        }
                        float sampleValue(SamplerContext context, int x, int y, int z);
                        record Bound(DensitySampler sampler, SamplerContext context) {
                            public float sampleValue(int x, int y, int z) { return sampler.sampleValue(context, x, y, z); }
                        }
                    }""",
            "net/minecraft/world/level/levelgen/densityfunction/SamplerContext.java", """
                    package net.minecraft.world.level.levelgen.densityfunction;
                    public class SamplerContext {
                        public static final SamplerContext EMPTY_UNCACHED = new SamplerContext();
                        public static int open;
                        public ScopedDensityBuffer acquireBuffer(DensityVolume volume) { open++; return new ScopedDensityBuffer(volume.size()); }
                    }""",
            "net/minecraft/world/level/levelgen/densityfunction/DensityBuffer.java", """
                    package net.minecraft.world.level.levelgen.densityfunction;
                    public class DensityBuffer {
                        private final float[] values;
                        public DensityBuffer(int size) { values = new float[size]; }
                        public void set(int index, float value) { values[index] = value; }
                        public float get(int index) { return values[index]; }
                    }""",
            "net/minecraft/world/level/levelgen/densityfunction/DensityVolume.java", """
                    package net.minecraft.world.level.levelgen.densityfunction;
                    public record DensityVolume(int sizeX, int sizeY, int sizeZ, int minBlockX, int minBlockY, int minBlockZ) {
                        public static final int NO_BLOCK = -1;
                        public int size() { return sizeX * sizeY * sizeZ; }
                        public int blockX(int i) { return minBlockX + i; }
                        public int blockY(int i) { return minBlockY + i; }
                        public int blockZ(int i) { return minBlockZ + i; }
                        public int indexOfBlock(int x, int y, int z) {
                            int ix = x - minBlockX, iy = y - minBlockY, iz = z - minBlockZ;
                            if (ix < 0 || iy < 0 || iz < 0 || ix >= sizeX || iy >= sizeY || iz >= sizeZ) return NO_BLOCK;
                            return iy + (ix + iz * sizeX) * sizeY;
                        }
                    }""",
            "net/minecraft/world/level/levelgen/densityfunction/DfRewriteRule.java", """
                    package net.minecraft.world.level.levelgen.densityfunction;
                    public interface DfRewriteRule { DensityFunction rewrite(DensityFunction function); }""",
            "net/minecraft/world/level/levelgen/densityfunction/op/CacheFunction.java", """
                    package net.minecraft.world.level.levelgen.densityfunction.op;
                    import net.minecraft.util.Interval;
                    import net.minecraft.world.level.levelgen.densityfunction.*;
                    public record CacheFunction(DensityFunction input) implements DensityFunction {
                        public DensitySampler compileSampler(CompileContext c) { throw new IllegalStateException("Cannot compile cache before it has been deduplicated"); }
                        public DensityFunction rewriteChildren(DfRewriteRule rule) { return new CacheFunction(rule.rewrite(input)); }
                        public Interval range() { return input.range(); }
                        public int domainAxes() { return ALL_AXES; }
                        public com.mojang.serialization.MapCodec<CacheFunction> codec() { return null; }
                    }""",
            "net/minecraft/world/level/levelgen/densityfunction/DensityFunctionCompiler.java", """
                    package net.minecraft.world.level.levelgen.densityfunction;
                    import net.minecraft.core.Holder;
                    import net.minecraft.util.Interval;
                    import net.minecraft.world.level.levelgen.FakeNoise;
                    import net.minecraft.world.level.levelgen.densityfunction.op.CacheFunction;
                    import net.minecraft.world.level.levelgen.synth.Noise;
                    import net.minecraft.world.level.levelgen.synth.NormalNoise;
                    public final class DensityFunctionCompiler {
                        public static int prepared;
                        static final DensityFunction.CompileContext CONTEXT = new DensityFunction.CompileContext() {
                            public Noise createNoiseSampler(Holder<NormalNoise> noise) {
                                return new Noise() {
                                    public Interval range() { return Interval.of(-0.75f, 0.75f); }
                                    public float get(double x, double y, double z) { return (float) FakeNoise.at(x, y, z); }
                                };
                            }
                            public net.minecraft.util.RandomSource createRandom(net.minecraft.resources.Identifier id) { return null; }
                            public net.minecraft.util.RandomSource createEndIslandRandom() { return null; }
                        };
                        public static DensitySampler compile(DensityFunction function) {
                            DfRewriteRule dedup = new DfRewriteRule() {
                                public DensityFunction rewrite(DensityFunction f) {
                                    if (f instanceof CacheFunction cache) {
                                        prepared++;
                                        return new DensityFunctions.Prepared(rewrite(cache.input()).compileSampler(CONTEXT), cache.input().range());
                                    }
                                    return f.rewriteChildren(this);
                                }
                            };
                            DensityFunction rewritten = dedup.rewrite(function);
                            DensitySampler sampler = rewritten.compileSampler(CONTEXT);
                            // As 26.3 slices a function without the y axis: one sample per column, at height 0.
                            if ((rewritten.domainAxes() & 2) != 0) return sampler;
                            return DensityFunctions.sampler((ctx, x, y, z) -> sampler.sampleValue(ctx, x, 0, z));
                        }
                        public static float sample(DensitySampler sampler, int x, int y, int z) {
                            return sampler.sampleValue(SamplerContext.EMPTY_UNCACHED, x, y, z);
                        }
                    }""",
            "net/minecraft/world/level/levelgen/densityfunction/DensityFunctions.java", """
                    package net.minecraft.world.level.levelgen.densityfunction;
                    import com.mojang.serialization.MapCodec;
                    import net.minecraft.core.Holder;
                    import net.minecraft.util.Interval;
                    import net.minecraft.world.level.levelgen.densityfunction.op.CacheFunction;
                    public final class DensityFunctions {
                        public static DensityFunction constant(float value) { return new Constant(value); }
                        public static DensityFunction add(DensityFunction a, DensityFunction b) { return new Add(a, b); }
                        public static DensityFunction cache(DensityFunction input) { return new CacheFunction(input); }
                        public static DensityFunction interpolated(DensityFunction input, int cellXz, int cellY) { return new Interpolated(input, cellXz, cellY); }
                        public static int points, volumes;
                        /** Its volume path adds 1000, so a value read from a volume is told apart from a point. */
                        public static DensityFunction counted(DensityFunction input) { return new Counted(input); }
                        public record Counted(DensityFunction input) implements DensityFunction {
                            public DensitySampler compileSampler(CompileContext c) {
                                DensitySampler in = input.compileSampler(c);
                                return new DensitySampler() {
                                    public float sampleValue(SamplerContext ctx, int x, int y, int z) { points++; return in.sampleValue(ctx, x, y, z); }
                                    public void sampleVolume(SamplerContext ctx, DensityBuffer b, DensityVolume v) {
                                        volumes++;
                                        int i = 0;
                                        for (int z = 0; z < v.sizeZ(); z++) for (int x = 0; x < v.sizeX(); x++) for (int y = 0; y < v.sizeY(); y++)
                                            b.set(i++, in.sampleValue(ctx, v.blockX(x), v.blockY(y), v.blockZ(z)) + 1000);
                                    }
                                };
                            }
                            public DensityFunction rewriteChildren(DfRewriteRule rule) { return new Counted(rule.rewrite(input)); }
                            public Interval range() { return Interval.INFINITE; }
                            public int domainAxes() { return input.domainAxes(); }
                            public MapCodec<Counted> codec() { return null; }
                        }
                        public record Interpolated(DensityFunction input, int cellXz, int cellY) implements DensityFunction {
                            public DensitySampler compileSampler(CompileContext c) { return input.compileSampler(c); }
                            public DensityFunction rewriteChildren(DfRewriteRule rule) { return new Interpolated(rule.rewrite(input), cellXz, cellY); }
                            public Interval range() { return input.range(); }
                            public int domainAxes() { return input.domainAxes(); }
                            public MapCodec<Interpolated> codec() { return null; }
                        }
                        public static DensityFunction noise(Holder<net.minecraft.world.level.levelgen.synth.NormalNoise> noise) { return new NoiseFunction(noise); }
                        public static DensityFunction yClampedGradient(int fromY, int toY, float from, float to) { return new YGradient(fromY, toY, from, to); }
                        /** A function of x and z only, as a 2D noise is. */
                        public static DensityFunction xz() { return new Xz(); }
                        public record Xz() implements DensityFunction {
                            public DensitySampler compileSampler(CompileContext c) { return sampler((ctx, x, y, z) -> ((x * 3 + z * 5) % 11) / 11f); }
                            public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }
                            public Interval range() { return Interval.of(-1, 1); }
                            public int domainAxes() { return 5; }
                            public MapCodec<Xz> codec() { return null; }
                        }
                        public record YGradient(int fromY, int toY, float from, float to) implements DensityFunction {
                            public DensitySampler compileSampler(CompileContext c) {
                                return sampler((ctx, x, y, z) -> from + (to - from) * Math.max(0, Math.min(1, (y - fromY) / (float) (toY - fromY))));
                            }
                            public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }
                            public Interval range() { return Interval.of(Math.min(from, to), Math.max(from, to)); }
                            public int domainAxes() { return 2; }
                            public MapCodec<YGradient> codec() { return null; }
                        }
                        interface Point { float at(SamplerContext context, int x, int y, int z); }
                        static DensitySampler sampler(Point point) {
                            return new DensitySampler() {
                                public void sampleVolume(SamplerContext c, DensityBuffer b, DensityVolume v) { DensitySampler.sampleVolumeNaive(c, b, v, this); }
                                public float sampleValue(SamplerContext c, int x, int y, int z) { return point.at(c, x, y, z); }
                            };
                        }
                        public record Constant(float value) implements DensityFunction {
                            public DensitySampler compileSampler(CompileContext c) { return sampler((ctx, x, y, z) -> value); }
                            public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }
                            public Interval range() { return Interval.of(value, value); }
                            public int domainAxes() { return 0; }
                            public MapCodec<Constant> codec() { return null; }
                        }
                        public record Add(DensityFunction a, DensityFunction b) implements DensityFunction {
                            public DensitySampler compileSampler(CompileContext c) {
                                DensitySampler left = a.compileSampler(c), right = b.compileSampler(c);
                                return sampler((ctx, x, y, z) -> left.sampleValue(ctx, x, y, z) + right.sampleValue(ctx, x, y, z));
                            }
                            public DensityFunction rewriteChildren(DfRewriteRule rule) { return new Add(rule.rewrite(a), rule.rewrite(b)); }
                            public Interval range() { return Interval.of(a.range().min() + b.range().min(), a.range().max() + b.range().max()); }
                            public int domainAxes() { return ALL_AXES; }
                            public MapCodec<Add> codec() { return null; }
                        }
                        public record Clamp(DensityFunction input, float min, float max) implements DensityFunction {
                            public DensitySampler compileSampler(CompileContext c) {
                                DensitySampler in = input.compileSampler(c);
                                return sampler((ctx, x, y, z) -> Math.max(min, Math.min(max, in.sampleValue(ctx, x, y, z))));
                            }
                            public DensityFunction rewriteChildren(DfRewriteRule rule) { return new Clamp(rule.rewrite(input), min, max); }
                            public Interval range() { return Interval.of(min, max); }
                            public int domainAxes() { return ALL_AXES; }
                            public MapCodec<Clamp> codec() { return null; }
                        }
                        public record Reciprocal(DensityFunction input) implements DensityFunction {
                            public DensitySampler compileSampler(CompileContext c) {
                                DensitySampler in = input.compileSampler(c);
                                return sampler((ctx, x, y, z) -> 1.0f / in.sampleValue(ctx, x, y, z));
                            }
                            public DensityFunction rewriteChildren(DfRewriteRule rule) { return new Reciprocal(rule.rewrite(input)); }
                            public Interval range() { return Interval.INFINITE; }
                            public int domainAxes() { return ALL_AXES; }
                            public MapCodec<Reciprocal> codec() { return null; }
                        }
                        public record NoiseFunction(Holder<net.minecraft.world.level.levelgen.synth.NormalNoise> noise) implements DensityFunction {
                            public DensitySampler compileSampler(CompileContext c) {
                                net.minecraft.world.level.levelgen.synth.Noise sampled = c.createNoiseSampler(noise);
                                return sampler((ctx, x, y, z) -> sampled.get(x, y, z));
                            }
                            public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }
                            public Interval range() { return Interval.of(-0.75f, 0.75f); }
                            public int domainAxes() { return ALL_AXES; }
                            public MapCodec<NoiseFunction> codec() { return null; }
                        }
                        public record Prepared(DensitySampler sampler, Interval range) implements DensityFunction {
                            public DensitySampler compileSampler(CompileContext c) { return sampler; }
                            public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }
                            public int domainAxes() { return ALL_AXES; }
                            public MapCodec<Prepared> codec() { return null; }
                        }
                        public record HolderHolder(Holder<DensityFunction> function) implements DensityFunction {
                            public DensitySampler compileSampler(CompileContext c) { return function.value().compileSampler(c); }
                            public DensityFunction rewriteChildren(DfRewriteRule rule) {
                                DensityFunction rewritten = rule.rewrite(function.value());
                                return rewritten == function.value() ? this : rewritten;
                            }
                            public Interval range() { return function.value().range(); }
                            public int domainAxes() { return function.value().domainAxes(); }
                            public MapCodec<HolderHolder> codec() { return null; }
                        }
                    }""")));

    /** 26.2's shape: each function implements mapChildren, and mapAll carries a visitor through it. */
    private static final Map<String, String> OLD_HOST_262 = merge(COMMON, Map.of(
            "net/minecraft/util/KeyDispatchDataCodec.java",
            "package net.minecraft.util; public final class KeyDispatchDataCodec<A> {}",
            "net/minecraft/world/level/levelgen/DensityFunction.java", """
                    package net.minecraft.world.level.levelgen;
                    import net.minecraft.util.KeyDispatchDataCodec;
                    public interface DensityFunction {
                        double compute(FunctionContext context);
                        void fillArray(double[] values, ContextProvider provider);
                        DensityFunction mapChildren(Visitor visitor);
                        default DensityFunction mapAll(Visitor visitor) {
                            Visitor recursive = new Visitor() { public DensityFunction apply(DensityFunction f) { return visitor.apply(f.mapChildren(this)); } };
                            return recursive.apply(this);
                        }
                        double minValue();
                        double maxValue();
                        KeyDispatchDataCodec<? extends DensityFunction> codec();
                        default DensityFunction invert() { return DensityFunctions.invert(this); }
                        interface FunctionContext { int blockX(); int blockY(); int blockZ(); }
                        interface ContextProvider { FunctionContext forIndex(int index); void fillAllDirectly(double[] values, DensityFunction function); }
                        interface SimpleFunction extends DensityFunction {
                            default void fillArray(double[] values, ContextProvider provider) { provider.fillAllDirectly(values, this); }
                            default DensityFunction mapChildren(Visitor visitor) { return this; }
                        }
                        record SinglePointContext(int blockX, int blockY, int blockZ) implements FunctionContext {}
                        interface Visitor { DensityFunction apply(DensityFunction function); }
                    }""",
            "net/minecraft/world/level/levelgen/DensityFunctions.java", """
                    package net.minecraft.world.level.levelgen;
                    import net.minecraft.core.Holder;
                    import net.minecraft.util.KeyDispatchDataCodec;
                    public final class DensityFunctions {
                        public static DensityFunction constant(double value) { return new Constant(value); }
                        public static DensityFunction add(DensityFunction a, DensityFunction b) { return new Add(a, b); }
                        static DensityFunction invert(DensityFunction input) { return new Invert(input); }
                        abstract static class Base implements DensityFunction {
                            public void fillArray(double[] values, ContextProvider provider) { provider.fillAllDirectly(values, this); }
                            public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
                        }
                        static final class Constant extends Base {
                            final double value;
                            Constant(double value) { this.value = value; }
                            public double compute(FunctionContext c) { return value; }
                            public DensityFunction mapChildren(Visitor v) { return this; }
                            public double minValue() { return value; }
                            public double maxValue() { return value; }
                        }
                        static final class Add extends Base {
                            final DensityFunction a, b;
                            Add(DensityFunction a, DensityFunction b) { this.a = a; this.b = b; }
                            public double compute(FunctionContext c) { return a.compute(c) + b.compute(c); }
                            public DensityFunction mapChildren(Visitor v) { return new Add(v.apply(a), v.apply(b)); }
                            public double minValue() { return a.minValue() + b.minValue(); }
                            public double maxValue() { return a.maxValue() + b.maxValue(); }
                        }
                        static final class Invert extends Base {
                            final DensityFunction input;
                            Invert(DensityFunction input) { this.input = input; }
                            public double compute(FunctionContext c) { return 1.0 / input.compute(c); }
                            public DensityFunction mapChildren(Visitor v) { return new Invert(v.apply(input)); }
                            public double minValue() { return Double.NEGATIVE_INFINITY; }
                            public double maxValue() { return Double.POSITIVE_INFINITY; }
                        }
                        public record HolderHolder(Holder<DensityFunction> function) implements DensityFunction {
                            public double compute(FunctionContext c) { return function.value().compute(c); }
                            public void fillArray(double[] values, ContextProvider provider) { function.value().fillArray(values, provider); }
                            public DensityFunction mapChildren(Visitor v) { return new HolderHolder(Holder.direct(v.apply(function.value()))); }
                            public double minValue() { return function.value().minValue(); }
                            public double maxValue() { return function.value().maxValue(); }
                            public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
                        }
                    }""",
            "net/minecraft/world/level/levelgen/RandomState.java", """
                    package net.minecraft.world.level.levelgen;
                    public final class RandomState {
                        public static DensityFunction wire(DensityFunction function) { return function.mapAll(f -> f); }
                    }"""));

    /** A mod built for 26.2: its function implements mapChildren and calls mapAll and invert. */
    private static final Map<String, String> MOD_262 = Map.of(
            "mod/Offset.java", """
                    package mod;
                    import net.minecraft.util.KeyDispatchDataCodec;
                    import net.minecraft.world.level.levelgen.DensityFunction;
                    public record Offset(DensityFunction input, double amount) implements DensityFunction {
                        public double compute(FunctionContext c) { return input.compute(c) + amount + c.blockX() * 0.01; }
                        public void fillArray(double[] values, ContextProvider provider) { provider.fillAllDirectly(values, this); }
                        public DensityFunction mapChildren(Visitor v) { return new Offset(v.apply(input), amount); }
                        public double minValue() { return input.minValue() + amount - 1; }
                        public double maxValue() { return input.maxValue() + amount + 1; }
                        public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
                    }""",
            "mod/Calls.java", """
                    package mod;
                    import net.minecraft.core.Holder;
                    import net.minecraft.world.level.levelgen.DensityFunction;
                    import net.minecraft.world.level.levelgen.DensityFunctions;
                    public final class Calls {
                        public static DensityFunction tree() {
                            DensityFunction inner = new Offset(DensityFunctions.constant(0.25), 1.0);
                            DensityFunction tree = new Offset(DensityFunctions.add(DensityFunctions.constant(0.5),
                                    new DensityFunctions.HolderHolder(Holder.direct(inner))), 2.0);
                            // A visitor through mapAll doubles every Offset's amount, so the tree must be walked to match.
                            return tree.mapAll(f -> f instanceof Offset o ? new Offset(o.input(), o.amount() * 2) : f);
                        }
                        public static DensityFunction inverted() { return new Offset(DensityFunctions.constant(4.0), 0.0).invert(); }
                        public static double sample(DensityFunction function, int x, int y, int z) {
                            return function.compute(new DensityFunction.SinglePointContext(x, y, z));
                        }
                    }""");

    /** The old mod: its own functions, with children, a noise and a marker, and the old calls on them. */
    private static final Map<String, String> MOD = Map.of(
            "mod/Scale.java", """
                    package mod;
                    import net.minecraft.util.KeyDispatchDataCodec;
                    import net.minecraft.world.level.levelgen.DensityFunction;
                    public record Scale(DensityFunction input, double factor) implements DensityFunction {
                        public double compute(FunctionContext c) { return input.compute(c) * factor + c.blockY() * 0.001; }
                        public void fillArray(double[] values, ContextProvider provider) { provider.fillAllDirectly(values, this); }
                        public DensityFunction mapAll(Visitor v) { return v.apply(new Scale(input.mapAll(v), factor)); }
                        public double minValue() { return input.minValue() * factor - 1; }
                        public double maxValue() { return input.maxValue() * factor + 1; }
                        public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
                    }""",
            "mod/Wave.java", """
                    package mod;
                    import net.minecraft.util.KeyDispatchDataCodec;
                    import net.minecraft.world.level.levelgen.DensityFunction;
                    public record Wave() implements DensityFunction.SimpleFunction {
                        public double compute(FunctionContext c) { return ((c.blockX() * 3 + c.blockZ() * 5) % 11) / 11.0; }
                        public double minValue() { return -1; }
                        public double maxValue() { return 1; }
                        public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
                    }""",
            "mod/Base.java", """
                    package mod;
                    import net.minecraft.util.KeyDispatchDataCodec;
                    import net.minecraft.world.level.levelgen.DensityFunction;
                    public class Base implements DensityFunction {
                        protected final DensityFunction a;
                        public Base(DensityFunction a) { this.a = a; }
                        public double compute(FunctionContext c) { return a.compute(c); }
                        public void fillArray(double[] v, ContextProvider p) { p.fillAllDirectly(v, this); }
                        public DensityFunction mapAll(Visitor v) { return v.apply(rebuild(a.mapAll(v))); }
                        protected DensityFunction rebuild(DensityFunction child) { return new Base(child); }
                        public double minValue() { return Double.NEGATIVE_INFINITY; }
                        public double maxValue() { return Double.POSITIVE_INFINITY; }
                        public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
                    }""",
            "mod/SubY.java", """
                    package mod;
                    import net.minecraft.world.level.levelgen.DensityFunction;
                    public class SubY extends Base {
                        public SubY(DensityFunction a) { super(a); }
                        public double compute(FunctionContext c) { return withY(c); }
                        private double withY(FunctionContext c) { return a.compute(c) + c.blockY() * 0.01; }
                        protected DensityFunction rebuild(DensityFunction child) { return new SubY(child); }
                    }""",
            "mod/Hidden.java", """
                    package mod;
                    import net.minecraft.util.KeyDispatchDataCodec;
                    import net.minecraft.world.level.levelgen.DensityFunction;
                    import net.minecraft.world.level.levelgen.DensityFunctions;
                    public record Hidden(DensityFunction a) implements DensityFunction {
                        static final DensityFunction Y_GRAD = DensityFunctions.yClampedGradient(-64, 320, -1.0, 1.0);
                        public double compute(FunctionContext c) { return a.compute(c) + Y_GRAD.compute(c); }
                        public void fillArray(double[] v, ContextProvider p) { p.fillAllDirectly(v, this); }
                        public DensityFunction mapAll(Visitor v) { return v.apply(new Hidden(a.mapAll(v))); }
                        public double minValue() { return a.minValue() - 1; }
                        public double maxValue() { return a.maxValue() + 1; }
                        public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
                    }""",
            "mod/Shift.java", """
                    package mod;
                    import net.minecraft.util.KeyDispatchDataCodec;
                    import net.minecraft.world.level.levelgen.DensityFunction;
                    public record Shift(DensityFunction input, int dx) implements DensityFunction {
                        public double compute(FunctionContext c) { return input.compute(new SinglePointContext(c.blockX() + dx, c.blockY(), c.blockZ())); }
                        public void fillArray(double[] values, ContextProvider provider) { provider.fillAllDirectly(values, this); }
                        public DensityFunction mapAll(Visitor v) { return v.apply(new Shift(input.mapAll(v), dx)); }
                        public double minValue() { return input.minValue(); }
                        public double maxValue() { return input.maxValue(); }
                        public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
                    }""",
            "mod/Marker.java", """
                    package mod;
                    import net.minecraft.util.KeyDispatchDataCodec;
                    import net.minecraft.world.level.levelgen.DensityFunction;
                    public record Marker() implements DensityFunction.SimpleFunction {
                        public double compute(FunctionContext c) { throw new IllegalStateException("a marker was never swapped"); }
                        public double minValue() { return 0; }
                        public double maxValue() { return 0; }
                        public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
                    }""",
            "mod/NoiseRead.java", """
                    package mod;
                    import net.minecraft.util.KeyDispatchDataCodec;
                    import net.minecraft.world.level.levelgen.DensityFunction;
                    public record NoiseRead(DensityFunction.NoiseHolder noise, DensityFunction offset) implements DensityFunction {
                        public double compute(FunctionContext c) { return noise.getValue(c.blockX() * 0.5, c.blockY(), c.blockZ() * 0.5) + offset.compute(c); }
                        public void fillArray(double[] values, ContextProvider provider) { provider.fillAllDirectly(values, this); }
                        public DensityFunction mapAll(Visitor v) { return v.apply(new NoiseRead(v.visitNoise(noise), offset.mapAll(v))); }
                        public double minValue() { return -noise.maxValue(); }
                        public double maxValue() { return noise.maxValue(); }
                        public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
                    }""",
            "mod/Calls.java", """
                    package mod;
                    import net.minecraft.core.Holder;
                    import net.minecraft.world.level.biome.Climate;
                    import net.minecraft.world.level.levelgen.DensityFunction;
                    import net.minecraft.world.level.levelgen.DensityFunctions;
                    public final class Calls {
                        public static DensityFunction wave() { return new Wave(); }
                        public static DensityFunction tree() {
                            DensityFunction inner = DensityFunctions.add(DensityFunctions.constant(0.25), new DensityFunctions.HolderHolder(Holder.direct(new Wave())));
                            return DensityFunctions.add(new Scale(DensityFunctions.cache(inner), 2.0).clamp(-0.5, 1.5), DensityFunctions.constant(-0.125));
                        }
                        public static DensityFunction noisy() {
                            return new NoiseRead(new DensityFunction.NoiseHolder(Holder.direct(new Object())),
                                    DensityFunctions.add(DensityFunctions.noise(Holder.direct(new Object())), DensityFunctions.constant(0.5)));
                        }
                        public static DensityFunction hostNoise() {
                            return DensityFunctions.add(DensityFunctions.noise(Holder.direct(new Object())), DensityFunctions.constant(0.0));
                        }
                        public static DensityFunction swapped() {
                            DensityFunction wrapper = DensityFunctions.add(DensityFunctions.constant(1.0), new DensityFunctions.HolderHolder(Holder.direct(new Marker())));
                            DensityFunction original = new Scale(new Wave(), 3.0);
                            return wrapper.mapAll(f -> f instanceof Marker ? original : f);
                        }
                        public static DensityFunction cells(DensityFunction input) { return DensityFunctions.interpolated(DensityFunctions.flatCache(input)); }
                        public static DensityFunction holderWith(net.minecraft.world.level.levelgen.synth.NormalNoise noise) {
                            return new NoiseRead(new DensityFunction.NoiseHolder(Holder.direct(new Object()), noise), DensityFunctions.constant(0.0));
                        }
                        public static DensityFunction swapIn(DensityFunction tree, DensityFunction replacement) {
                            return tree.mapAll(f -> f instanceof Marker ? replacement : f);
                        }
                        public static DensityFunction markerReference() { return new DensityFunctions.HolderHolder(Holder.direct(new Marker())); }
                        public static double sample(DensityFunction function, int x, int y, int z) {
                            return function.compute(new DensityFunction.SinglePointContext(x, y, z));
                        }
                        public static double endErosion(Climate.Sampler sampler, int x, int y, int z) {
                            return sampler.erosion().compute(new DensityFunction.SinglePointContext(x, y, z));
                        }
                        public static double[] fill(DensityFunction function) {
                            double[] values = new double[12];
                            function.fillArray(values, new DensityFunction.ContextProvider() {
                                public DensityFunction.FunctionContext forIndex(int i) { return new DensityFunction.SinglePointContext(i * 3, i, -i * 2); }
                                public void fillAllDirectly(double[] out, DensityFunction f) {
                                    for (int i = 0; i < out.length; i++) out[i] = f.compute(forIndex(i));
                                }
                            });
                            return values;
                        }
                    }""");

    private static Map<String, String> merge(Map<String, String> first, Map<String, String> second) {
        Map<String, String> merged = new HashMap<>(first);
        merged.putAll(second);
        return merged;
    }
}
