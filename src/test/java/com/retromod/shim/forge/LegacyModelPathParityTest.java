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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Every class-transform path sends old render type sets and int[] quads to the same embedded rebuilt classes, nested jars too. */
class LegacyModelPathParityTest {

    private static final String CALLER = LegacyForgeModelBridgeTest.CALLER;
    private static final String MODEL = LegacyForgeModelBridge.ROOT;
    // The render type set and its layer table, which NeoForge's model bridge shares, live outside the Forge package.
    private static final String SHARED_MODEL = "com/retromod/legacyapi/minecraft/client/model/";

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
    @DisplayName("The Forge runtime, CLI batch, CLI transform and AOT paths agree on every rebuilt model call")
    void everyPathAgrees(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("models.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("models.jar"));
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
            expected.put(prefix + "groups", "[LegacyRenderTypeGroups.create, LegacyRenderTypeGroups.block, RenderTypeGroup.entity]"
                    + " on embedded copies");
            expected.put(prefix + "lambda", "LegacyQuadTransformer on an embedded copy");
            expected.put(prefix + "layers", "[LegacyChunkLayers.getRenderLayers, ChunkRenderTypeSet.contains,"
                    + " LegacyChunkLayers.setRenderLayer] on embedded copies");
            expected.put(prefix + "quads", "[LegacyQuadVertices.create, LegacyQuadVertices.getVertices,"
                    + " LegacyQuadVertices.getTintIndex, LegacyQuadVertices.getSprite, LegacyQuadVertices.isShade,"
                    + " LegacyQuadVertices.hasAmbientOcclusion, BakedQuad.direction] on embedded copies");
            expected.put(prefix + "transformers", "[LegacyQuadTransformers.settingEmissivity,"
                    + " LegacyQuadTransformer.processInPlace, LegacyQuadTransformer.STRIDE] on embedded copies");
        }
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey());
        }
    }

    /** What RetromodForge registers on the host, shims and then the vanilla class moves, then one runtime transform. */
    private Path forgeRuntime(Path input, Path dir, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        com.retromod.mapping.IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
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

    // Each method's model calls by simple owner and name, and whether every rebuilt owner is a copy inside this jar.
    private static void describe(JarFile jar, String prefix, Map<String, String> found) throws Exception {
        ClassNode node = node(jar, CALLER);
        for (String name : List.of("layers", "groups", "transformers", "quads")) {
            List<String> calls = new java.util.ArrayList<>();
            boolean embedded = true;
            for (AbstractInsnNode insn : method(node, name).instructions) {
                String owner = null;
                String member = null;
                if (insn instanceof MethodInsnNode call) {
                    owner = call.owner;
                    member = call.name;
                } else if (insn instanceof FieldInsnNode field) {
                    owner = field.owner;
                    member = field.name;
                }
                if (owner == null) continue;
                calls.add(owner.substring(owner.lastIndexOf('/') + 1) + "." + member);
                if (owner.contains(MODEL) || owner.contains(SHARED_MODEL)) embedded &= isEmbedded(jar, owner);
                else embedded &= !owner.startsWith("net/minecraftforge/client/model/") && !owner.contains("ChunkRenderTypeSet");
            }
            found.put(prefix + name, calls + (embedded ? " on embedded copies" : " on other owners"));
        }
        for (AbstractInsnNode insn : method(node, "lambda").instructions) {
            if (insn instanceof InvokeDynamicInsnNode lambda) {
                String type = lambda.desc.substring(3, lambda.desc.length() - 1);
                found.put(prefix + "lambda", type.substring(type.lastIndexOf('/') + 1)
                        + (isEmbedded(jar, type) ? " on an embedded copy" : " on " + type));
            }
        }
    }

    private static boolean isEmbedded(JarFile jar, String owner) {
        return owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(owner + ".class") != null;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName() + ": " + new TreeSet<>(jar.stream().map(JarEntry::getName).toList()));
        return ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
    }

    /** A Forge 1.20.1 mod with the model helper, named as before the 26.1 class moves, and a nested library mod with the same. */
    private static byte[] modJar() throws Exception {
        byte[] calls = LegacyForgeModelBridgeTest.modelCalls(LegacyForgeModelBridge.OLD_QUAD,
                "net/minecraft/client/renderer/RenderType");
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("fixturelib"));
        lib.put("fixturelib/LibMod.class", ModConstructorHooksTest.modClass("fixturelib/LibMod", "fixturelib",
                LegacyAutoSubscribeBridgeTest.OBJECT, (writer, owner) -> { }));
        lib.put(CALLER + ".class", calls);
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("modelmod"));
        mod.put("mod/ModelMod.class", ModConstructorHooksTest.modClass("mod/ModelMod", "modelmod",
                LegacyAutoSubscribeBridgeTest.OBJECT, (writer, owner) -> { }));
        mod.put(CALLER + ".class", calls);
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }
}
