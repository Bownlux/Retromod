/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.loaderapi.CliHarness;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.common.LegacyItemPropertiesFixtures;
import com.retromod.shim.common.LegacyItemPropertiesFixtures.Loader;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Every transform path relinks old ItemProperties calls to the jar's embedded copy and writes the same item definition. */
class LegacyItemPropertiesPathParityTest {

    private static final String COPY = "com/retromod/legacyapi/minecraft/client/renderer/item/";
    private static final String TYPE = "retromod:legacy_item_property";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({"FORGE_1201, forge, 26.2", "FORGE_1201, forge, 26.3", "NEOFORGE_1211, neoforge, 1.21.11",
            "NEOFORGE_1211, neoforge, 26.1", "NEOFORGE_1211, neoforge, 26.3", "FORGE_1201, neoforge, 26.2", "FABRIC_1211, fabric, 26.1",
            "FABRIC_1211, fabric, 26.3"})
    @DisplayName("The runtime, CLI batch, CLI transform and AOT paths agree on the calls, the lambdas, the copy and the definition")
    void everyPathAgrees(Loader fixture, String loader, String host, @TempDir Path dir) throws Exception {
        useLoader(loader);
        Path input = dir.resolve("gems.jar");
        Files.write(input, LegacyItemPropertiesFixtures.modJar(fixture, dir));

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("runtime", summary(runtime(input, dir, loader, host), fixture));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("gems.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(onlyJar(batchOut), fixture));
        Path transformed = dir.resolve("transformed.jar");
        CliHarness.transform(input, transformed, host);
        outputs.put("CLI transform", summary(transformed, fixture));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot, fixture));

        Map<String, String> expected = expected(fixture);
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), fixture + " on " + loader + " " + host + ", " + output.getKey());
        }
    }

    private void useLoader(String loader) {
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader.equals("fabric") ? null : loader);
    }

    private static Map<String, String> expected(Loader fixture) {
        boolean fabric = fixture == Loader.FABRIC_1211;
        String clamped = fabric ? "ClampedItemPropertyFunction" : "ItemPropertyFunction";
        Map<String, String> expected = new TreeMap<>();
        for (String prefix : List.of("", "nested:")) {
            String namespace = prefix.isEmpty() ? "gemmod" : "fixturelib";
            // Fabric's three calls pass clamped functions, and Fabric API's registry reaches the same register.
            expected.put(prefix + "register", fabric
                    ? "[ItemProperties.register(Item,Identifier,ClampedItemPropertyFunction)void,"
                    + " ItemProperties.register(Item,Identifier,ClampedItemPropertyFunction)void,"
                    + " ItemProperties.registerGeneric(Identifier,ClampedItemPropertyFunction)ClampedItemPropertyFunction] on the embedded copy"
                    : "[ItemProperties.register(Item,Identifier,ItemPropertyFunction)void,"
                    + " ItemProperties.register(Item,Identifier," + (fixture == Loader.FORGE_1201 ? "ItemPropertyFunction" : "ClampedItemPropertyFunction")
                    + ")void, ItemProperties.registerGeneric(Identifier,ItemPropertyFunction)ItemPropertyFunction] on the embedded copy");
            expected.put(prefix + "read", "[ItemProperties.getProperty(" + (fixture == Loader.FORGE_1201 ? "Item" : "ItemStack")
                    + ",Identifier)ItemPropertyFunction, ItemPropertyFunction.call(ItemStack,ClientLevel,LivingEntity,int)float]"
                    + " on the embedded copy");
            expected.put(prefix + "lambdas", "[" + (fabric ? "ClampedItemPropertyFunction" : "ItemPropertyFunction") + ", "
                    + (fabric ? clamped : "ClampedItemPropertyFunction") + ", " + clamped + "] on the embedded copy");
            expected.put(prefix + "copy", "ItemProperties, its two function types and the shared store; no property type");
            expected.put(prefix + "definition", "range " + namespace + ":charge [0.5 -> range minecraft:cooking [1.0 -> "
                    + namespace + ":item/gem_cooking] else " + namespace + ":item/gem_charged] else range minecraft:cooking [1.0 -> "
                    + namespace + ":item/gem_cooking] else " + namespace + ":item/gem");
        }
        return expected;
    }

    /** The registrations a loader's entry point makes for the host, then one runtime transform. */
    private Path runtime(Path input, Path dir, String loader, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Path out = Files.createDirectory(dir.resolve("runtime"));
        if (loader.equals("fabric")) {
            RetromodPreLaunch.registerShimsForTransform(host);
            Path written = new FabricModTransformer(host).transformMod(input, out);
            assertNotNull(written, "the Fabric runtime wrote a jar");
            return written;
        }
        Set<String> loaders = loader.equals("neoforge") ? Set.of("neoforge", "forge", "common") : Set.of("forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (loaders.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        com.retromod.mapping.IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        Path written = new ForgeModTransformer(host).transformMod(input, out);
        assertNotNull(written, "the runtime wrote a jar");
        return written;
    }

    private static Path onlyJar(Path dir) throws Exception {
        try (var jars = Files.list(dir)) {
            List<Path> all = jars.filter(path -> path.toString().endsWith(".jar")).toList();
            assertEquals(1, all.size(), "the batch wrote one jar: " + all);
            return all.get(0);
        }
    }

    private static Map<String, String> summary(Path jar, Loader fixture) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describe(file, "oldmod/Props", "gemmod", "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                JarEntry entry = file.getJarEntry(LegacyItemPropertiesFixtures.nestedPath(fixture));
                assertNotNull(entry, "the nested library is still in " + jar);
                Files.write(nested, file.getInputStream(entry).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "fixturelib/Props", "fixturelib", "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static void describe(JarFile jar, String props, String namespace, String prefix, Map<String, String> found)
            throws Exception {
        ClassNode node = node(jar, props);
        found.put(prefix + "register", calls(jar, method(node, "register")));
        found.put(prefix + "read", calls(jar, method(node, "read")));
        found.put(prefix + "lambdas", lambdas(jar, method(node, "register")));
        found.put(prefix + "copy", copy(jar));
        found.put(prefix + "definition", definition(jar, namespace));
    }

    // Each call into the old API, by simple names, and whether every owner is the jar's own embedded copy.
    private static String calls(JarFile jar, MethodNode method) {
        List<String> seen = new ArrayList<>();
        boolean embedded = true;
        for (AbstractInsnNode insn : method.instructions) {
            // Only the old API's calls; the stack's own getItem stays on the host.
            if (!(insn instanceof MethodInsnNode call) || !isOldApi(call.owner)) continue;
            seen.add(simple(call.owner) + "." + call.name + signature(call.desc));
            embedded &= isEmbedded(jar, call.owner);
        }
        return seen + (embedded ? " on the embedded copy" : " on another owner");
    }

    private static String lambdas(JarFile jar, MethodNode method) {
        List<String> seen = new ArrayList<>();
        boolean embedded = true;
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
            String type = Type.getReturnType(indy.desc).getInternalName();
            seen.add(simple(type));
            embedded &= isEmbedded(jar, type);
        }
        return seen + (embedded ? " on the embedded copy" : " on another owner");
    }

    // The mod's copy: what its calls need, and none of the runtime-only property type, which Retromod itself adds.
    private static String copy(JarFile jar) {
        List<String> names = new ArrayList<>();
        for (JarEntry entry : Collections.list(jar.entries())) {
            String name = entry.getName();
            if (name.startsWith(SyntheticEmbedder.PREFIX) && name.contains(COPY) && name.endsWith(".class")) {
                names.add(name.substring(name.indexOf(COPY) + COPY.length(), name.length() - ".class".length()));
            }
        }
        Collections.sort(names);
        boolean needed = names.containsAll(List.of("ItemProperties", "ItemPropertyFunction", "ClampedItemPropertyFunction",
                "LegacyItemPropertyStore"));
        boolean typeLeftOut = names.stream().noneMatch(name -> name.startsWith("LegacyItemPropertyType")
                || name.equals("LegacyItemProperty"));
        return needed && typeLeftOut ? "ItemProperties, its two function types and the shared store; no property type" : names.toString();
    }

    private static String definition(JarFile jar, String namespace) throws Exception {
        JarEntry entry = jar.getJarEntry("assets/" + namespace + "/items/gem.json");
        if (entry == null) return "no definition";
        JsonObject model = JsonParser.parseString(new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("model");
        return tree(model);
    }

    // A range on Retromod's type as "range <id> [threshold -> node] else <fallback>", a plain model as its id.
    private static String tree(JsonObject node) {
        String type = node.get("type").getAsString();
        if (type.equals("minecraft:model")) return node.get("model").getAsString();
        if (!type.equals("minecraft:range_dispatch") || !TYPE.equals(node.get("property").getAsString())) return node.toString();
        StringBuilder text = new StringBuilder("range ").append(node.get("id").getAsString()).append(" [");
        List<String> entries = new ArrayList<>();
        for (JsonElement element : node.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            entries.add(entry.get("threshold").getAsDouble() + " -> " + tree(entry.getAsJsonObject("model")));
        }
        return text.append(String.join(", ", entries)).append("] else ").append(tree(node.getAsJsonObject("fallback"))).toString();
    }

    private static boolean isOldApi(String owner) {
        String name = simple(owner);
        return name.contains("ItemPropert") || name.startsWith("class_") || name.contains("PredicateProvider");
    }

    private static String signature(String desc) {
        Type method = Type.getMethodType(desc);
        List<String> arguments = new ArrayList<>();
        for (Type argument : method.getArgumentTypes()) arguments.add(simple(argument.getClassName().replace('.', '/')));
        return "(" + String.join(",", arguments) + ")" + simple(method.getReturnType().getClassName().replace('.', '/'));
    }

    private static String simple(String name) {
        return name.substring(name.lastIndexOf('/') + 1);
    }

    private static boolean isEmbedded(JarFile jar, String owner) {
        return owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(owner + ".class") != null;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name));
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }
}
