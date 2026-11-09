/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.FabricModTransformer;
import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import static org.objectweb.asm.Opcodes.*;

/** Every transform path gives an old renderer's early getEntityModels() call the embedded forwarding set, nested jars too. */
class LegacyEntityModelsPathParityTest {

    private static final String SET = "net/minecraft/client/model/geom/EntityModelSet";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String GET_INSTANCE = MINECRAFT + ".getInstance()L" + MINECRAFT + ";";
    // The host calls the embedded set makes, which must keep the host's names in every copy.
    private static final Set<String> HOST_CALLS = new TreeSet<>(Set.of(GET_INSTANCE,
            MINECRAFT + ".getModelManager()Lnet/minecraft/client/resources/model/ModelManager;",
            "net/minecraft/client/resources/model/ModelManager.entityModels()Ljava/util/function/Supplier;",
            SET + ".bakeLayer(Lnet/minecraft/client/model/geom/ModelLayerLocation;)Lnet/minecraft/client/model/geom/ModelPart;"));

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void reset() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(false);
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
    @DisplayName("Forge: the runtime, CLI batch, CLI transform and AOT paths bridge a Forge 1.20.1 mod's call")
    void forge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceForge(true);
        assertEveryPathAgrees(host, dir, Fixture.FORGE_1201);
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("NeoForge: every path bridges a NeoForge 1.21.1 mod's call and a Forge 1.20.1 mod's")
    void neoForge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceNeoForge(true);
        assertEveryPathAgrees(host, dir.resolve("neo"), Fixture.NEOFORGE_1211);
        transformer.clearRedirectsForTesting();
        assertEveryPathAgrees(host, dir.resolve("forge"), Fixture.FORGE_1201);
    }

    @ParameterizedTest(name = "Fabric {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("Fabric: every path bridges a Fabric 1.21.1 mod's call after the intermediary remap")
    void fabric(String host, @TempDir Path dir) throws Exception {
        assertEveryPathAgrees(host, dir, Fixture.FABRIC_1211);
    }

    private void assertEveryPathAgrees(String host, Path dir, Fixture fixture) throws Exception {
        Files.createDirectories(dir);
        Path input = dir.resolve("skillet.jar");
        Files.write(input, modJar(fixture));

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("runtime", summary(runtime(input, dir, host, fixture), fixture));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("skillet.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut), fixture));
        Path transformed = dir.resolve("transformed.jar");
        CliHarness.transform(input, transformed, host);
        outputs.put("CLI transform", summary(transformed, fixture));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot, fixture));

        Map<String, String> expected = new TreeMap<>();
        for (String prefix : List.of("", "nested:")) {
            expected.put(prefix + "renderer", "[" + GET_INSTANCE + ", embedded LegacyEntityModels.entityModels(L"
                    + MINECRAFT + ";)L" + SET + ";]");
            expected.put(prefix + "embedded", "extends " + SET + ", overrides bakeLayer, calls " + HOST_CALLS);
        }
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), fixture + " on " + host + ", " + output.getKey());
        }
    }

    /** What the loader's entry point registers on the host, mappings and shims, then one runtime transform. */
    private Path runtime(Path input, Path dir, String host, Fixture fixture) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Path out = Files.createDirectory(dir.resolve("runtime"));
        if (fixture == Fixture.FABRIC_1211) {
            IntermediaryToMojangMapper.applyTo(transformer);
            registerShims(host, Set.of("fabric", "common"));
            return new FabricModTransformer(host).transformMod(input, out);
        }
        // RetromodForge and RetromodNeoForge decode the mod's SRG names on Mojang-named hosts.
        SrgToMojangMapper.getInstance().applyTo(transformer);
        registerShims(host, McReflect.isNeoForge() ? Set.of("neoforge", "forge", "common") : Set.of("forge", "common"));
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        Path written = new ForgeModTransformer(host).transformMod(input, out);
        assertNotNull(written, "the runtime wrote a jar");
        return written;
    }

    private void registerShims(String host, Set<String> loaders) {
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (loaders.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
    }

    private static Map<String, String> summary(Path jar, Fixture fixture) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describe(file, "mod/SkilletRenderer", "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry(fixture.nested)).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "fixturelib/LibRenderer", "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    // The renderer's constructor calls, and the embedded set they reach.
    private static void describe(JarFile jar, String rendererClass, String prefix, Map<String, String> found) throws Exception {
        List<String> calls = new ArrayList<>();
        String embedded = null;
        for (AbstractInsnNode insn : method(node(jar, rendererClass), "<init>").instructions) {
            if (!(insn instanceof MethodInsnNode call) || call.name.equals("<init>")) continue;
            boolean isEmbedded = call.owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(call.owner + ".class") != null;
            if (isEmbedded) embedded = call.owner;
            calls.add(isEmbedded ? "embedded " + call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name + call.desc
                    : call.owner + "." + call.name + call.desc);
        }
        found.put(prefix + "renderer", calls.toString());
        found.put(prefix + "embedded", embedded == null ? "none" : embeddedSet(node(jar, embedded)));
    }

    private static String embeddedSet(ClassNode set) {
        Set<String> hostCalls = new TreeSet<>();
        for (MethodNode method : set.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && !call.owner.startsWith(SyntheticEmbedder.PREFIX)
                        && !call.owner.startsWith("java/") && !call.name.equals("<init>")) {
                    hostCalls.add(call.owner + "." + call.name + call.desc);
                }
            }
        }
        boolean overrides = set.methods.stream().anyMatch(m -> m.name.equals("bakeLayer")
                && m.desc.equals("(Lnet/minecraft/client/model/geom/ModelLayerLocation;)Lnet/minecraft/client/model/geom/ModelPart;"));
        return "extends " + set.superName + (overrides ? ", overrides bakeLayer" : ", no bakeLayer") + ", calls " + hostCalls;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
    }

    /** The names an old mod's call is written in: SRG for Forge 1.20.1, Mojang for NeoForge 1.21.1, intermediary for Fabric 1.21.1. */
    enum Fixture {
        FORGE_1201(MINECRAFT, "m_91087_", "m_167973_", SET, "META-INF/jarjar/lib.jar"),
        NEOFORGE_1211(MINECRAFT, "getInstance", "getEntityModels", SET, "META-INF/jarjar/lib.jar"),
        FABRIC_1211("net/minecraft/class_310", "method_1551", "method_31974", "net/minecraft/class_5599", "META-INF/jars/lib.jar");

        final String minecraft;
        final String getInstance;
        final String getEntityModels;
        final String set;
        final String nested;

        Fixture(String minecraft, String getInstance, String getEntityModels, String set, String nested) {
            this.minecraft = minecraft;
            this.getInstance = getInstance;
            this.getEntityModels = getEntityModels;
            this.set = set;
            this.nested = nested;
        }
    }

    /** A mod whose renderer keeps the entity models it asks for in its constructor, and a nested library mod with the same. */
    private static byte[] modJar(Fixture fixture) throws Exception {
        Map<String, byte[]> lib = files("fixturelib", "fixturelib/LibRenderer", fixture, null);
        Map<String, byte[]> mod = files("skilletmod", "mod/SkilletRenderer", fixture, fixture.nested);
        mod.put(fixture.nested, LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static Map<String, byte[]> files(String modId, String rendererClass, Fixture fixture, String nestedJar) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        switch (fixture) {
            case FORGE_1201 -> files.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml(modId));
            case NEOFORGE_1211 -> files.put("META-INF/neoforge.mods.toml", LegacyItemRendererPathParityTest.neoforgeToml(modId));
            case FABRIC_1211 -> files.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0\","
                    + "\"depends\":{\"minecraft\":\"1.21.1\"}" + (nestedJar == null ? "" : ",\"jars\":[{\"file\":\"" + nestedJar + "\"}]")
                    + "}").getBytes(StandardCharsets.UTF_8));
        }
        files.put(rendererClass + ".class", renderer(rendererClass, fixture));
        return files;
    }

    // As Farmer's Delight's skillet renderer: the constructor asks Minecraft for its entity models and keeps them.
    private static byte[] renderer(String name, Fixture fixture) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "models", "L" + fixture.set + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESTATIC, fixture.minecraft, fixture.getInstance, "()L" + fixture.minecraft + ";", false);
        init.visitMethodInsn(INVOKEVIRTUAL, fixture.minecraft, fixture.getEntityModels, "()L" + fixture.set + ";", false);
        init.visitFieldInsn(PUTFIELD, name, "models", "L" + fixture.set + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

}
