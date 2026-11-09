/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.common.LegacyArmPoseRuntimeTest;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Every class-transform path sends a Forge 1.20.1 arm pose to the embedded helper, outer and nested jar alike. */
class LegacyArmPosePathParityTest {

    private static final String FORGE_TRANSFORMER = "net/minecraftforge/client/IArmPoseTransformer";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI batch, CLI transform and AOT paths agree on create, the transformer and the frame time")
    void everyPathAgrees(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("poses.jar");
        Files.write(input, modJar(LegacyArmPoseRuntimeTest.oldModClasses(dir.resolve("old"))));

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("poses.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut)));
        Path transformed = dir.resolve("transformed.jar");
        CliHarness.transform(input, transformed, host);
        outputs.put("CLI transform", summary(transformed));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        Map<String, String> expected = new TreeMap<>();
        for (String prefix : new String[]{"", "nested:"}) {
            expected.put(prefix + "Poses.<clinit>", "[LegacyArmPoses.create x4] embedded");
            expected.put(prefix + "FlipTransformer", "implements [LegacyArmPoseTransformer] embedded");
            expected.put(prefix + "FlipTransformer.applyTransform", "[LegacyFrameTime.getFrameTime] embedded");
            expected.put(prefix + "Adapter", "implements [" + FORGE_TRANSFORMER + "]");
        }
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey());
        }
    }

    /** What RetromodForge.loadForgeShims registers on the host, then one runtime transform. */
    private Path forgeRuntime(Path input, Path dir, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        Path out = new ForgeModTransformer(host).transformMod(input, Files.createDirectory(dir.resolve("runtime")));
        assertNotNull(out, "the Forge runtime wrote a jar");
        return out;
    }

    private static Map<String, String> summary(Path jar) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describe(file, "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static void describe(JarFile jar, String prefix, Map<String, String> found) throws Exception {
        found.put(prefix + "Poses.<clinit>", calls(jar, node(jar, "mod/Poses"), "<clinit>", "create"));
        ClassNode flip = node(jar, "mod/FlipTransformer");
        found.put(prefix + "FlipTransformer", "implements [" + simpleName(flip.interfaces.get(0)) + "]"
                + (embedded(jar, flip.interfaces.get(0)) ? " embedded" : " elsewhere"));
        found.put(prefix + "FlipTransformer.applyTransform", calls(jar, flip, "applyTransform", "getFrameTime"));
        // The embedded adapter, which the mod's class redirect must leave on the host's interface.
        String helper = null;
        for (MethodNode method : node(jar, "mod/Poses").methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("create")) helper = call.owner;
            }
        }
        found.put(prefix + "Adapter", "implements " + node(jar, helper + "$Adapter").interfaces);
    }

    /** The calls named {@code name} in {@code method}, by owner's simple name, and whether every owner is embedded here. */
    private static String calls(JarFile jar, ClassNode owner, String method, String name) {
        java.util.List<String> calls = new java.util.ArrayList<>();
        boolean allEmbedded = true;
        for (MethodNode candidate : owner.methods) {
            if (!candidate.name.equals(method)) continue;
            for (AbstractInsnNode insn : candidate.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals(name)) {
                    calls.add(simpleName(call.owner) + "." + call.name);
                    allEmbedded &= embedded(jar, call.owner);
                }
            }
        }
        String text = calls.size() > 1 && calls.stream().distinct().count() == 1
                ? "[" + calls.get(0) + " x" + calls.size() + "]" : calls.toString();
        return text + (allEmbedded ? " embedded" : " elsewhere");
    }

    private static String simpleName(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    private static boolean embedded(JarFile jar, String owner) {
        return owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(owner + ".class") != null;
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    /** The old mod with its arm poses, and a nested library mod carrying the same classes. */
    private static byte[] modJar(Map<String, byte[]> classes) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("poselib"));
        classes.forEach((name, bytes) -> lib.put(name + ".class", bytes));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("posemod"));
        classes.forEach((name, bytes) -> mod.put(name + ".class", bytes));
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }
}
