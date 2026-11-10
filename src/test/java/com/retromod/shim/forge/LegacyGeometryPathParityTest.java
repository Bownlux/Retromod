/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Every transform path sends old geometry to the same embedded classes and gives a separate_transforms item its select, nested jars too. */
class LegacyGeometryPathParityTest {

    private static final String UMBRELLA = "{\"loader\":\"forge:separate_transforms\",\"base\":{\"parent\":\"%1$s:item/flat\"},"
            + "\"perspectives\":{\"gui\":{\"parent\":\"%1$s:item/held\"}}}";
    private static final String FLAT = "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"%1$s:item/flat\"}}";

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
    @DisplayName("The Forge runtime, CLI batch, CLI transform and AOT paths agree on geometry calls, installs and the select")
    void everyPathAgrees(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("geometry.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("geometry.jar"));
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
            String modClass = prefix.isEmpty() ? "mod/GeometryMod" : "fixturelib/LibMod";
            String namespace = prefix.isEmpty() ? "geomod" : "fixturelib";
            expected.put(prefix + modClass, "installs [LegacyClientRenderBootstrap, LegacyGeometryLoaders, LegacyModelEvents]"
                    + " from the embedded copy");
            expected.put(prefix + "registerLoaders", "[LegacyGeometryLoaders.register] on embedded copies");
            expected.put(prefix + "nested", "[LegacyModelBakers.getModel, LegacyModelBakers.resolveParents, LegacyModelBakers.bake,"
                    + " LegacyModelBakers.bake, LegacyModelBakers.getModelTextureGetter, LegacyModelBakers.getModel] on embedded copies");
            expected.put(prefix + "loader", "implements the embedded LegacyGeometryLoader");
            expected.put(prefix + "umbrella", "select on minecraft:display_context, " + namespace + ":item/held for [gui], fallback "
                    + namespace + ":item/flat");
            expected.put(prefix + "embedded", "the adapter implements the host's IGeometryLoader; register(Identifier,"
                    + " IGeometryLoader) in LegacyGeometryLoaders and LegacyBuiltinLoaders; Forge's context bake in"
                    + " LegacyUnbakedGeometryAdapter and LegacyBuiltinLoaders$Composite");
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
            describe(file, "mod/GeometryMod", "geomod", "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "fixturelib/LibMod", "fixturelib", "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static void describe(JarFile jar, String modClass, String namespace, String prefix, Map<String, String> found)
            throws Exception {
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

        ClassNode calls = node(jar, LegacyForgeGeometryBridgeTest.CALLS);
        for (String name : List.of("registerLoaders", "nested")) {
            List<String> seen = new ArrayList<>();
            boolean embedded = true;
            for (AbstractInsnNode insn : method(calls, name).instructions) {
                if (!(insn instanceof MethodInsnNode call) || call.owner.startsWith("java/")) continue;
                seen.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
                embedded &= call.owner.contains("com/retromod/legacyapi/") && isEmbedded(jar, call.owner);
            }
            found.put(prefix + name, seen + (embedded ? " on embedded copies" : " on other owners"));
        }
        ClassNode loader = node(jar, LegacyForgeGeometryBridgeTest.LOADER);
        String iface = loader.interfaces.get(0);
        found.put(prefix + "loader", iface.endsWith("legacyapi/forge/client/model/LegacyGeometryLoader") && isEmbedded(jar, iface)
                ? "implements the embedded LegacyGeometryLoader" : "implements " + iface);

        found.put(prefix + "embedded", embedded(jar));

        JarEntry definition = jar.getJarEntry("assets/" + namespace + "/items/umbrella.json");
        assertNotNull(definition, "an item definition for " + namespace + ":umbrella");
        JsonObject model = JsonParser.parseString(new String(jar.getInputStream(definition).readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("model");
        JsonObject first = model.has("cases") ? model.getAsJsonArray("cases").get(0).getAsJsonObject() : new JsonObject();
        found.put(prefix + "umbrella", model.has("cases")
                ? "select on " + model.get("property").getAsString() + ", " + first.getAsJsonObject("model").get("model").getAsString()
                        + " for " + first.getAsJsonArray("when").toString().replace("\"", "") + ", fallback "
                        + model.getAsJsonObject("fallback").get("model").getAsString()
                : model.toString());
    }

    private static final String HOST_LOADER = "net/minecraftforge/client/model/geometry/IGeometryLoader";
    private static final String HOST_REGISTER = LegacyForgeGeometryBridge.REGISTER_GEOMETRY_LOADERS
            + ".register(Lnet/minecraft/resources/Identifier;L" + HOST_LOADER + ";)V";
    private static final String CONTEXT_BAKE = "Lnet/minecraftforge/client/model/geometry/IGeometryBakingContext;)"
            + "Lnet/minecraft/client/resources/model/geometry/QuadCollection;";

    // The embedded copy itself, which is written for the host and must keep the host's names a version-limited
    // redirect sends older mods elsewhere.
    private static String embedded(JarFile jar) throws Exception {
        List<String> wrong = new ArrayList<>();
        ClassNode adapter = embeddedNode(jar, "LegacyGeometryLoaderAdapter");
        if (!adapter.interfaces.contains(HOST_LOADER)) wrong.add("the adapter implements " + adapter.interfaces);
        for (String owner : List.of("LegacyGeometryLoaders", "LegacyBuiltinLoaders")) {
            List<String> registers = new ArrayList<>();
            for (MethodNode method : embeddedNode(jar, owner).methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.name.equals("register")
                            && call.owner.equals(LegacyForgeGeometryBridge.REGISTER_GEOMETRY_LOADERS)) {
                        registers.add(call.owner + "." + call.name + call.desc);
                    }
                }
            }
            if (registers.isEmpty() || !registers.stream().allMatch(HOST_REGISTER::equals)) wrong.add(owner + " calls " + registers);
        }
        for (String owner : List.of("LegacyUnbakedGeometryAdapter", "LegacyBuiltinLoaders$Composite")) {
            boolean bakes = embeddedNode(jar, owner).methods.stream()
                    .anyMatch(m -> m.name.equals("bake") && m.desc.endsWith(CONTEXT_BAKE));
            if (!bakes) wrong.add(owner + " has no bake taking Forge's context");
        }
        return wrong.isEmpty() ? "the adapter implements the host's IGeometryLoader; register(Identifier, IGeometryLoader) in"
                + " LegacyGeometryLoaders and LegacyBuiltinLoaders; Forge's context bake in LegacyUnbakedGeometryAdapter and"
                + " LegacyBuiltinLoaders$Composite" : wrong.toString();
    }

    private static ClassNode embeddedNode(JarFile jar, String simpleName) throws Exception {
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

    /** A Forge 1.20.1 mod with a geometry loader, its registration, nested model calls and a separate_transforms item, and a nested library with the same. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = files("fixturelib", "fixturelib/LibMod");
        Map<String, byte[]> mod = files("geomod", "mod/GeometryMod");
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static Map<String, byte[]> files(String modId, String modClass) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml(modId));
        files.put(modClass + ".class", ModConstructorHooksTest.modClass(modClass, modId, LegacyAutoSubscribeBridgeTest.OBJECT,
                (writer, owner) -> { }));
        files.put(LegacyForgeGeometryBridgeTest.CALLS + ".class", LegacyForgeGeometryBridgeTest.geometryCalls());
        files.put(LegacyForgeGeometryBridgeTest.LOADER + ".class", LegacyForgeGeometryBridgeTest.loader());
        files.put("assets/" + modId + "/models/item/umbrella.json", UMBRELLA.formatted(modId).getBytes(StandardCharsets.UTF_8));
        files.put("assets/" + modId + "/models/item/flat.json", FLAT.formatted(modId).getBytes(StandardCharsets.UTF_8));
        files.put("assets/" + modId + "/models/item/held.json", FLAT.formatted(modId).getBytes(StandardCharsets.UTF_8));
        return files;
    }
}
