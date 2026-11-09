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
import com.retromod.shim.neoforge.LegacyNeoForgeColorBridgeTest;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.Handle;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** On NeoForge 26.x every class-transform path rebuilds the old color events, installs them and writes the item tints, nested jars too. */
class LegacyNeoColorPathParityTest {

    private static final String HANDLERS = "mod/ColorHandlers";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void neoForgeTarget() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("A NeoForge 1.21.1 mod: the runtime, CLI batch, CLI transform and AOT paths agree")
    void neoForgeModsAgree(String host, @TempDir Path dir) throws Exception {
        assertEveryPathAgrees(host, dir, modJar(true), true);
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("A Forge 1.20.1 mod on NeoForge: the runtime, CLI batch, CLI transform and AOT paths agree")
    void forgeModsAgree(String host, @TempDir Path dir) throws Exception {
        assertEveryPathAgrees(host, dir, modJar(false), false);
    }

    private void assertEveryPathAgrees(String host, Path dir, byte[] jar, boolean neoForgeMod) throws Exception {
        Path input = dir.resolve("colors.jar");
        Files.write(input, jar);

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("runtime", summary(runtime(input, dir, host)));
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

        // A 1.21.1 mod's item colors keep their alpha; a 1.20.1 mod's are drawn opaque and its BlockColors.getColor answers RGB.
        String itemRegister = neoForgeMod ? "register" : "registerOpaque";
        String getColor = neoForgeMod ? "getColor" : "getColorRgb";
        Map<String, String> expected = new TreeMap<>();
        for (String mod : List.of("colormod", "fixturelib")) {
            String prefix = mod.equals("colormod") ? "" : "nested:";
            String modClass = mod.equals("colormod") ? "mod/ColorMod" : "fixturelib/LibMod";
            expected.put(prefix + modClass, "installs [LegacyNeoForgeColors] from the embedded copy");
            expected.put(prefix + "onBlockColors", "takes LegacyBlockColorsEvent, calls [LegacyBlockColorsEvent.getBlockColors,"
                    + " LegacyBlockColorsEvent.register] embedded");
            expected.put(prefix + "onItemColors", "takes LegacyItemColorsEvent, calls [LegacyItemColorsEvent." + itemRegister + "] embedded");
            expected.put(prefix + "subscribe", "a method reference to onItemColors(LegacyItemColorsEvent)");
            expected.put(prefix + "lateSetup", "takes BlockColors, calls [Minecraft.getInstance, ItemColors.legacyOf, ItemColors." + itemRegister
                    + ", LegacyBlockColorCalls.register, LegacyBlockColorCalls." + getColor + "] embedded");
            expected.put(prefix + "gem", "[" + mod + ":retromod_legacy_color 0, " + mod + ":retromod_legacy_color 1]");
            expected.put(prefix + "leaf", "[" + mod + ":retromod_legacy_color 0]");
            expected.put(prefix + "stone", "untinted");
        }
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), (neoForgeMod ? "NeoForge 1.21.1" : "Forge 1.20.1") + " mod on " + host
                    + ", " + output.getKey());
        }
    }

    /** What RetromodNeoForge registers on the host, shims and then the vanilla class moves, then one runtime transform. */
    private Path runtime(Path input, Path dir, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("neoforge", "forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        com.retromod.mapping.IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        Path out = new ForgeModTransformer(host).transformMod(input, Files.createDirectory(dir.resolve("runtime")));
        assertNotNull(out, "the runtime wrote a jar");
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
                    installs.add(simple(call.owner));
                    embedded &= isEmbedded(jar, call.owner);
                }
            }
        }
        found.put(prefix + modClass, "installs " + installs + (embedded ? " from the embedded copy" : " from elsewhere"));

        ClassNode handlers = node(jar, HANDLERS);
        for (String name : List.of("onBlockColors", "onItemColors", "lateSetup")) {
            MethodNode method = LegacyForgeRenderBridgeTest.method(handlers, name);
            List<String> calls = new ArrayList<>();
            boolean allEmbedded = true;
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                calls.add(simple(call.owner) + "." + call.name);
                allEmbedded &= call.owner.equals("net/minecraft/client/Minecraft") || isEmbedded(jar, call.owner);
            }
            String parameter = Type.getArgumentTypes(method.desc)[0].getInternalName();
            found.put(prefix + name, "takes " + simple(parameter) + ", calls " + calls + (allEmbedded ? " embedded" : " on other owners"));
        }
        found.put(prefix + "subscribe", reference(jar, LegacyForgeRenderBridgeTest.method(handlers, "subscribe")));

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

    // The method an addListener method reference names, which is where NeoForge's bus reads the event type.
    private static String reference(JarFile jar, MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs[1] instanceof Handle target) {
                String parameter = Type.getArgumentTypes(target.getDesc())[0].getInternalName();
                return "a method reference to " + target.getName() + "(" + simple(parameter) + ")"
                        + (isEmbedded(jar, parameter) ? "" : " not embedded");
            }
        }
        return "no method reference";
    }

    private static String simple(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    private static boolean isEmbedded(JarFile jar, String owner) {
        return owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(owner + ".class") != null;
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
    }

    /** A NeoForge 1.21.1 or Forge 1.20.1 mod with color handlers and three item models, and a nested library mod with the same. */
    private static byte[] modJar(boolean neoForge) throws Exception {
        Map<String, byte[]> lib = files("fixturelib", "fixturelib/LibMod", neoForge);
        Map<String, byte[]> mod = files("colormod", "mod/ColorMod", neoForge);
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static Map<String, byte[]> files(String namespace, String modClass, boolean neoForge) {
        Map<String, byte[]> jar = new LinkedHashMap<>();
        if (neoForge) {
            jar.put("META-INF/neoforge.mods.toml", LegacyItemRendererPathParityTest.neoforgeToml(namespace));
            jar.put(modClass + ".class", LegacyNeoForgeColorBridgeTest.modClass(modClass, namespace));
            jar.put(HANDLERS + ".class", LegacyNeoForgeColorBridgeTest.neoHandlers(HANDLERS));
        } else {
            jar.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml(namespace));
            jar.put(modClass + ".class", ModConstructorHooksTest.modClass(modClass, namespace, LegacyAutoSubscribeBridgeTest.OBJECT,
                    (writer, owner) -> { }));
            jar.put(HANDLERS + ".class", LegacyNeoForgeColorBridgeTest.forgeHandlers(HANDLERS));
        }
        String models = "assets/" + namespace + "/models/";
        jar.put(models + "item/gem.json", ("{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"" + namespace
                + ":item/gem\",\"layer1\":\"" + namespace + ":item/gem_glow\"}}").getBytes(StandardCharsets.UTF_8));
        jar.put(models + "item/leaf.json", ("{\"parent\":\"" + namespace + ":block/leaf\"}").getBytes(StandardCharsets.UTF_8));
        jar.put(models + "block/leaf.json", "{\"parent\":\"minecraft:block/leaves\"}".getBytes(StandardCharsets.UTF_8));
        jar.put(models + "item/stone.json", "{\"parent\":\"minecraft:block/cube_all\"}".getBytes(StandardCharsets.UTF_8));
        return jar;
    }
}
