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

/** Every class-transform path sends old baked models, model locations and baking-result maps to the same embedded classes, nested jars too. */
class LegacyBakedModelPathParityTest {

    private static final String CALLER = LegacyForgeBakedModelBridgeTest.CALLER;
    private static final String WRAPPER = LegacyForgeBakedModelBridgeTest.WRAPPER;
    private static final String MODEL = LegacyForgeBakedModelBridge.ROOT;

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
    @DisplayName("The Forge runtime, CLI batch, CLI transform and AOT paths agree on every rebuilt model call and install")
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
            String modClass = prefix.isEmpty() ? "mod/ModelMod" : "fixturelib/LibMod";
            // A jar with old models also installs the geometry part, whose copy registers Retromod's forge: loaders.
            expected.put(prefix + modClass, "installs [LegacyClientRenderBootstrap, LegacyGeometryLoaders, LegacyModelEvents]"
                    + " from the embedded copy");
            expected.put(prefix + "locations", "[LegacyModelLocations.of, LegacyModelLocations.getVariant, LegacyModelLocations.path,"
                    + " LegacyModelLocations.isLocation, LegacyModelLocations.of,"
                    + " LegacyModelLocations.vanilla, LegacyModelLocations.forState, LegacyModelLocations.forState,"
                    + " LegacyModelLocations.missingModelLocation] on embedded copies");
            expected.put(prefix + "maps", "[LegacyModelMaps.getModels, LegacyModelMaps.getModels, LegacyModelMaps.getModel,"
                    + " LegacyModelMaps.getModel, LegacyModelMaps.getMissingModel, BakedModel.getQuads,"
                    + " LegacyRegisterAdditional.register] on embedded copies");
            expected.put(prefix + "wrapper", "extends the embedded BakedModelWrapper");
            expected.put(prefix + "extra models", "the pack the hooks list is an embedded LegacyExtraModelPack on the host's"
                    + " PathPackResources, and it waits for common setup through ModLoader.hasCompletedState(COMMON_SETUP)");
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
            describe(file, "mod/ModelMod", "", found);
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

    // Each method's calls by simple owner and name, whether every rebuilt owner is a copy inside this jar, and the installs.
    private static void describe(JarFile jar, String modClass, String prefix, Map<String, String> found) throws Exception {
        List<String> installs = new ArrayList<>();
        boolean installEmbedded = true;
        for (MethodNode method : node(jar, modClass).methods) {
            if (!method.name.equals("<init>")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("install")) {
                    installs.add(call.owner.substring(call.owner.lastIndexOf('/') + 1));
                    installEmbedded &= isEmbedded(jar, call.owner);
                }
            }
        }
        installs.sort(null);
        found.put(prefix + modClass, "installs " + installs + (installEmbedded ? " from the embedded copy" : " from elsewhere"));

        ClassNode node = node(jar, CALLER);
        for (String name : List.of("locations", "maps")) {
            List<String> calls = new ArrayList<>();
            boolean embedded = true;
            for (AbstractInsnNode insn : method(node, name).instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
                if (call.owner.contains("com/retromod/legacyapi/")) embedded &= isEmbedded(jar, call.owner);
            }
            found.put(prefix + name, calls + (embedded ? " on embedded copies" : " on other owners"));
        }
        found.put(prefix + "extra models", extraModels(jar));
        ClassNode wrapper = node(jar, WRAPPER);
        found.put(prefix + "wrapper", wrapper.superName.endsWith(MODEL.substring(MODEL.indexOf("legacyapi")) + "BakedModelWrapper")
                && isEmbedded(jar, wrapper.superName) ? "extends the embedded BakedModelWrapper" : "extends " + wrapper.superName);
    }

    // The embedded hooks' pack and its setup gate, which must keep the host's names in every copy.
    private static String extraModels(JarFile jar) throws Exception {
        List<String> wrong = new ArrayList<>();
        ClassNode hooks = embedded(jar, "LegacyModelEventHooks");
        ClassNode pack = embedded(jar, "LegacyExtraModelPack");
        ClassNode gate = embedded(jar, "LegacySetupGate");
        boolean opensPack = false;
        for (MethodNode method : hooks.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(pack.name) && call.name.equals("supplier")) opensPack = true;
            }
        }
        if (!opensPack) wrong.add("the hooks never list the pack");
        if (!pack.superName.equals("net/minecraft/server/packs/PathPackResources")) wrong.add("the pack extends " + pack.superName);
        boolean asksForge = false;
        boolean readsSetup = false;
        for (MethodNode method : gate.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals("net/minecraftforge/fml/ModLoader")
                        && call.name.equals("hasCompletedState")) asksForge = true;
                if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field && field.owner.equals("net/minecraftforge/fml/core/ModStateProvider")
                        && field.name.equals("COMMON_SETUP")) readsSetup = true;
            }
        }
        if (!asksForge || !readsSetup) wrong.add("the gate does not ask Forge whether common setup is done");
        return wrong.isEmpty() ? "the pack the hooks list is an embedded LegacyExtraModelPack on the host's PathPackResources, and it"
                + " waits for common setup through ModLoader.hasCompletedState(COMMON_SETUP)" : wrong.toString();
    }

    private static ClassNode embedded(JarFile jar, String simpleName) throws Exception {
        String suffix = "/com/retromod/legacyapi/forge/client/model/" + simpleName + ".class";
        for (JarEntry entry : java.util.Collections.list(jar.entries())) {
            if (entry.getName().startsWith(SyntheticEmbedder.PREFIX) && entry.getName().endsWith(suffix)) {
                return ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
            }
        }
        throw new AssertionError(simpleName + " is not embedded in " + jar.getName());
    }

    private static boolean isEmbedded(JarFile jar, String owner) {
        return owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(owner + ".class") != null;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
    }

    /** A Forge 1.20.1 mod with the model helper and a wrapper, and a nested library mod with the same. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("fixturelib"));
        lib.put("fixturelib/LibMod.class", ModConstructorHooksTest.modClass("fixturelib/LibMod", "fixturelib",
                LegacyAutoSubscribeBridgeTest.OBJECT, (writer, owner) -> { }));
        lib.put(CALLER + ".class", LegacyForgeBakedModelBridgeTest.modelCalls());
        lib.put(WRAPPER + ".class", LegacyForgeBakedModelBridgeTest.wrapper());
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("modelmod"));
        mod.put("mod/ModelMod.class", ModConstructorHooksTest.modClass("mod/ModelMod", "modelmod",
                LegacyAutoSubscribeBridgeTest.OBJECT, (writer, owner) -> { }));
        mod.put(CALLER + ".class", LegacyForgeBakedModelBridgeTest.modelCalls());
        mod.put(WRAPPER + ".class", LegacyForgeBakedModelBridgeTest.wrapper());
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }
}
