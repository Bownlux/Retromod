/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.google.gson.JsonElement;
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

/** Every class-transform path rebuilds the colors, installs item colors and writes the matching item tints, outer and nested jar alike. */
class LegacyColorPathParityTest {

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
    @DisplayName("The Forge runtime, CLI batch, CLI transform and AOT paths agree on the color calls, the install and the item tints")
    void everyPathAgrees(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("colors.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("colors.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut)));
        Path transformed = dir.resolve("transformed.jar");
        CliHarness.transform(input, transformed, host);
        outputs.put("CLI transform", summary(transformed));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        Map<String, String> expected = new TreeMap<>();
        for (String mod : List.of("colormod", "fixturelib")) {
            String prefix = mod.equals("colormod") ? "" : "nested:";
            String modClass = mod.equals("colormod") ? "mod/ColorMod" : "fixturelib/LibMod";
            expected.put(prefix + modClass, "installs [LegacyClientRenderBootstrap, LegacyItemColors] from the embedded copy");
            expected.put(prefix + "onBlockColors", "[LegacyBlockColors.register] embedded");
            expected.put(prefix + "lateSetup", "[Minecraft.getInstance, ItemColors.legacyOf, ItemColors.registerOpaque,"
                    + " LegacyBlockColorCalls.register, LegacyBlockColorCalls.getColorRgb] embedded");
            expected.put(prefix + "gem", "[" + mod + ":retromod_legacy_color 0, " + mod + ":retromod_legacy_color 1]");
            expected.put(prefix + "leaf", "[" + mod + ":retromod_legacy_color 0]");
            expected.put(prefix + "stone", "untinted");
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
            describe(file, "mod/ColorMod", "colormod", "", found);
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
        // The constructor's head calls, each to a class embedded in this jar.
        List<String> installs = new ArrayList<>();
        boolean embedded = true;
        for (MethodNode method : node(jar, modClass).methods) {
            if (!method.name.equals("<init>")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("install")) {
                    installs.add(call.owner.substring(call.owner.lastIndexOf('/') + 1));
                    embedded &= isEmbedded(jar, call.owner);
                }
            }
        }
        installs.sort(null);
        found.put(prefix + modClass, "installs " + installs + (embedded ? " from the embedded copy" : " from elsewhere"));

        ClassNode handlers = node(jar, "mod/ColorHandlers");
        for (String name : List.of("onBlockColors", "lateSetup")) {
            List<String> calls = new ArrayList<>();
            boolean allEmbedded = true;
            for (AbstractInsnNode insn : LegacyForgeRenderBridgeTest.method(handlers, name).instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
                allEmbedded &= call.owner.equals("net/minecraft/client/Minecraft") || isEmbedded(jar, call.owner);
            }
            found.put(prefix + name, calls + (allEmbedded ? " embedded" : " on other owners"));
        }

        for (String item : List.of("gem", "leaf", "stone")) {
            JarEntry entry = jar.getJarEntry("assets/" + namespace + "/items/" + item + ".json");
            assertNotNull(entry, item + " has a definition in " + jar.getName());
            JsonObject model = JsonParser.parseString(new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("model");
            if (!model.has("tints")) {
                found.put(prefix + item, "untinted");
                continue;
            }
            List<String> tints = new ArrayList<>();
            for (JsonElement tint : model.getAsJsonArray("tints")) {
                tints.add(tint.getAsJsonObject().get("type").getAsString() + " " + tint.getAsJsonObject().get("index").getAsInt());
            }
            found.put(prefix + item, tints.toString());
        }
    }

    private static boolean isEmbedded(JarFile jar, String owner) {
        return owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(owner + ".class") != null;
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
    }

    /** A Forge 1.20.1 mod with color handlers and three item models, and a nested library mod with the same. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("fixturelib"));
        addMod(lib, "fixturelib/LibMod", "fixturelib");
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("colormod"));
        addMod(mod, "mod/ColorMod", "colormod");
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static void addMod(Map<String, byte[]> jar, String modClass, String namespace) {
        jar.put(modClass + ".class", ModConstructorHooksTest.modClass(modClass, namespace, LegacyAutoSubscribeBridgeTest.OBJECT,
                (writer, owner) -> { }));
        jar.put("mod/ColorHandlers.class", LegacyForgeColorBridgeTest.handlers("mod/ColorHandlers"));
        String models = "assets/" + namespace + "/models/";
        jar.put(models + "item/gem.json", ("{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"" + namespace
                + ":item/gem\",\"layer1\":\"" + namespace + ":item/gem_glow\"}}").getBytes(StandardCharsets.UTF_8));
        jar.put(models + "item/leaf.json", ("{\"parent\":\"" + namespace + ":block/leaf\"}").getBytes(StandardCharsets.UTF_8));
        jar.put(models + "block/leaf.json", "{\"parent\":\"minecraft:block/leaves\"}".getBytes(StandardCharsets.UTF_8));
        jar.put(models + "item/stone.json", "{\"parent\":\"minecraft:block/cube_all\"}".getBytes(StandardCharsets.UTF_8));
    }
}
