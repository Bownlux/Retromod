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
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Every class-transform path rebuilds RenderLevelStageEvent and installs it the same way, outer and nested jar alike. */
class LegacyClientRenderPathParityTest {

    private static final String EVENT = LegacyForgeRenderBridge.EVENT;

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
    @DisplayName("The Forge runtime, CLI batch, CLI transform and AOT paths install the level stages and point listeners at the embedded event")
    void everyPathAgrees(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("stages.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("stages.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut)));
        Path transformed = dir.resolve("transformed.jar");
        CliHarness.transform(input, transformed, host);
        outputs.put("CLI transform", summary(transformed));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        Map<String, String> expected = new TreeMap<>();
        for (String prefix : List.of("", "nested:")) {
            String modClass = prefix.isEmpty() ? "mod/StageMod" : "fixturelib/LibMod";
            expected.put(prefix + modClass, "installs [LegacyClientRenderBootstrap, LegacyLevelStages] from the embedded copy");
            expected.put(prefix + "mod/StageListener", "takes the embedded event through the legacy dispatch");
            expected.put(prefix + "mod/ModelViewCaller", "[getStage, getModelViewMatrix] on the embedded event");
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
            describe(file, "mod/StageMod", "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "fixturelib/LibMod", "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static void describe(JarFile jar, String modClass, String prefix, Map<String, String> found) throws Exception {
        // The constructor's head calls, each to a class embedded in this jar.
        List<String> installs = new ArrayList<>();
        boolean embedded = true;
        for (MethodNode method : node(jar, modClass).methods) {
            if (!method.name.equals("<init>")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("install")) {
                    installs.add(call.owner.substring(call.owner.lastIndexOf('/') + 1));
                    embedded &= call.owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(call.owner + ".class") != null;
                }
            }
        }
        installs.sort(null);
        found.put(prefix + modClass, "installs " + installs + (embedded ? " from the embedded copy" : " from elsewhere"));

        MethodNode listener = method(node(jar, "mod/StageListener"), "onStage");
        String event = Type.getArgumentTypes(listener.desc)[0].getInternalName();
        boolean legacy = listener.visibleAnnotations != null && listener.visibleAnnotations.stream().map(a -> a.desc)
                .anyMatch(desc -> desc.startsWith("L" + SyntheticEmbedder.PREFIX) && desc.endsWith("/LegacySubscribe;"));
        boolean embeddedEvent = isEmbeddedEvent(jar, event);
        found.put(prefix + "mod/StageListener", (embeddedEvent ? "takes the embedded event" : "takes " + event)
                + (legacy ? " through the legacy dispatch" : " through " + annotations(listener)));

        List<String> calls = new ArrayList<>();
        boolean onEvent = true;
        for (AbstractInsnNode insn : method(node(jar, "mod/ModelViewCaller"), "onStage").instructions) {
            if (insn instanceof MethodInsnNode call) {
                calls.add(call.name);
                onEvent &= isEmbeddedEvent(jar, call.owner);
            }
        }
        found.put(prefix + "mod/ModelViewCaller", calls + (onEvent ? " on the embedded event" : " on another owner"));
    }

    private static boolean isEmbeddedEvent(JarFile jar, String owner) {
        return owner.startsWith(SyntheticEmbedder.PREFIX) && owner.endsWith(EVENT) && jar.getJarEntry(owner + ".class") != null;
    }

    private static List<String> annotations(MethodNode method) {
        List<String> found = new ArrayList<>();
        if (method.visibleAnnotations != null) for (AnnotationNode annotation : method.visibleAnnotations) found.add(annotation.desc);
        return found;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
    }

    /** A Forge 1.20.1 mod with a stage listener and a Forge 1.21-shaped model-view read, and a nested library mod with the same. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("fixturelib"));
        addClasses(lib, "fixturelib/LibMod", "fixturelib");
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("stagemod"));
        addClasses(mod, "mod/StageMod", "stagemod");
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static void addClasses(Map<String, byte[]> jar, String modClass, String modId) {
        jar.put(modClass + ".class", ModConstructorHooksTest.modClass(modClass, modId, LegacyAutoSubscribeBridgeTest.OBJECT,
                (writer, owner) -> { }));
        jar.put("mod/StageListener.class", LegacyForgeRenderBridgeTest.listenerClass("mod/StageListener"));
        jar.put("mod/ModelViewCaller.class", LegacyForgeRenderBridgeTest.modelViewCaller());
    }
}
