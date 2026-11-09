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

import java.io.IOException;
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

import static com.retromod.shim.forge.IngredientFixtures.*;
import static com.retromod.shim.forge.LegacyIngredientSerializerBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** Every class-transform path bridges an old serializer and ingredient the same way, outer and nested jar alike. */
class LegacyIngredientPathParityTest {

    private static final String OUTER_MOD = "mod/IngredientMod";
    private static final String NESTED_MOD = "fixturelib/LibMod";
    private static final String INGREDIENT_BRIDGED =
            "extends the embedded base, which takes the old values, and answers serializer()";

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
    @DisplayName("Forge runtime, CLI batch and AOT embed the same serializer, base, CraftingHelper and listener start")
    void everyPathAgrees(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("ingredients.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("ingredients.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        Map<String, String> expected = new TreeMap<>();
        for (String prefix : List.of("", "nested:")) {
            expected.put(prefix + "@Mod", "starts the listener through the embedded registration");
            expected.put(prefix + "ingredient", INGREDIENT_BRIDGED);
            expected.put(prefix + "serializer", "implements the embedded serializer");
            expected.put(prefix + "CraftingHelper", "5 calls, each declared on the embedded stand-in");
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
            boolean forgeShim = Set.of("forge", "common").contains(shim.getModLoaderType());
            if (forgeShim && ShimRegistry.isAvailableOnHost(shim, host)) {
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
            describe(file, OUTER_MOD, "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, NESTED_MOD, "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static void describe(JarFile jar, String modClass, String prefix, Map<String, String> found)
            throws Exception {
        boolean starts = calls(node(jar, modClass), SERIALIZERS, "watch").stream()
                .anyMatch(call -> embedded(jar, call.owner, SERIALIZERS));
        found.put(prefix + "@Mod", starts ? "starts the listener through the embedded registration"
                : "does not start it");

        ClassNode ingredient = node(jar, ITEM_INGREDIENT);
        boolean base = embedded(jar, ingredient.superName, BASE) && node(jar, ingredient.superName).methods.stream()
                .anyMatch(m -> m.name.equals("<init>") && m.desc.equals("(Ljava/util/stream/Stream;)V"));
        boolean answers = ingredient.methods.stream()
                .anyMatch(m -> m.name.equals(HOST_GETTER) && m.desc.equals(HOST_GETTER_DESC));
        found.put(prefix + "ingredient", (base ? "extends the embedded base, which takes the old values"
                : "extends " + ingredient.superName)
                + (answers ? ", and answers serializer()" : ", and has no serializer()"));

        List<String> interfaces = node(jar, ITEM_SERIALIZER).interfaces;
        found.put(prefix + "serializer", interfaces.size() == 1 && embedded(jar, interfaces.get(0), SERIALIZER)
                ? "implements the embedded serializer" : "implements " + interfaces);

        List<MethodInsnNode> helperCalls = calls(ingredient, CRAFTING_HELPER, null);
        long declared = helperCalls.stream().filter(call -> embedded(jar, call.owner, CRAFTING_HELPER)
                && node(jar, call.owner).methods.stream()
                        .anyMatch(m -> m.name.equals(call.name) && m.desc.equals(call.desc)))
                .count();
        found.put(prefix + "CraftingHelper", helperCalls.size() + " calls, " + (declared == helperCalls.size()
                ? "each declared on the embedded stand-in" : (helperCalls.size() - declared) + " not on the stand-in"));
    }

    /** Calls whose owner ends with {@code owner}, optionally of one name, anywhere in the class. */
    private static List<MethodInsnNode> calls(ClassNode node, String owner, String name) {
        List<MethodInsnNode> found = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.endsWith(owner)
                        && (name == null || call.name.equals(name))) {
                    found.add(call);
                }
            }
        }
        return found;
    }

    /** Whether {@code name} is the jar's own relocated copy of {@code original}. */
    private static boolean embedded(JarFile jar, String name, String original) {
        return name.startsWith(SyntheticEmbedder.PREFIX) && name.endsWith("/" + original)
                && jar.getJarEntry(name + ".class") != null;
    }

    private static ClassNode node(JarFile jar, String name) {
        try {
            JarEntry entry = jar.getJarEntry(name + ".class");
            assertNotNull(entry, name + " is missing from " + jar.getName());
            return IngredientFixtures.node(jar.getInputStream(entry).readAllBytes());
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    /** A Forge 1.20.1 mod with an old serializer and ingredient, and a nested library with the same. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("fixturelib"));
        addClasses(lib, NESTED_MOD, "fixturelib");
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("ingredientmod"));
        addClasses(mod, OUTER_MOD, "ingredientmod");
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static void addClasses(Map<String, byte[]> jar, String modClass, String modId) throws Exception {
        jar.put(modClass + ".class", ModConstructorHooksTest.modClass(modClass, modId,
                LegacyAutoSubscribeBridgeTest.OBJECT, (writer, owner) -> { }));
        for (Map.Entry<String, byte[]> entry : IngredientFixtures.oldMod().entrySet()) {
            jar.put(entry.getKey() + ".class", entry.getValue());
        }
    }
}
