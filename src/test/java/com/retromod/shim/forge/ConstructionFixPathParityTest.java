/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.loaderapi.CliHarness;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;

import static com.retromod.shim.forge.ConstructionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Every Forge transform path repairs the buffer renames, the old ingredient types and old event channels alike. */
class ConstructionFixPathParityTest {

    private static final String ID = "net/minecraft/resources/Identifier";
    private static final String EVENTS = "com/retromod/shim/forge/embedded/LegacyEventChannel";

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
        transformer.clearSrgNameMappings();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Forge runtime, CLI transform, CLI batch and AOT agree, outer and nested jar alike")
    void everyPathAgrees(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("cfix.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(transformer, input, dir, host)));
        Path transformed = dir.resolve("transformed.jar");
        CliHarness.transform(input, transformed, host);
        outputs.put("CLI transform", summary(transformed));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("cfix.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        Map<String, String> expected = new TreeMap<>();
        for (String prefix : List.of("", "nested:")) {
            expected.put(prefix + "buffer calls", "[" + BUF + ".readIdentifier()L" + ID + ";, " + BUF
                    + ".writeIdentifier(L" + ID + ";)L" + BUF + ";]");
            expected.put(prefix + "buffer method reference", BUF + ".writeIdentifier(L" + ID + ";)L" + BUF + ";");
            expected.put(prefix + "compound subclass", "extends the embedded LegacyCompoundIngredient,"
                    + " whose (List) constructor its super call names");
            expected.put(prefix + "factories", "[compound -> embedded LegacyCompoundIngredient.of,"
                    + " difference -> " + CRAFTING + "ingredients/DifferenceIngredient.of,"
                    + " intersection -> " + CRAFTING + "ingredients/IntersectionIngredient.of,"
                    + " partial -> " + CRAFTING + "ingredients/NBTIngredient.of,"
                    + " strict -> embedded LegacyStrictNBTIngredient.of]");
            String shimDirection = "com/retromod/shim/forge/embedded/NetworkShim$NetworkDirection";
            expected.put(prefix + "event channel open", EVENTS + ".open(Ljava/lang/Object;Ljava/util/function/Supplier;"
                    + "Ljava/util/function/Predicate;Ljava/util/function/Predicate;)L" + EVENTS + ";");
            expected.put(prefix + "event channel listen", EVENTS + ".addListener(Ljava/util/function/Consumer;)V");
            expected.put(prefix + "event channel payload", EVENTS + "$NetworkEvent.getPayload()Ljava/lang/Object;, cast " + BUF);
            expected.put(prefix + "event channel send", EVENTS + ".buildPacket(L" + shimDirection
                    + ";Ljava/lang/Object;Ljava/lang/Object;)L" + EVENTS + "$CustomPacket;, " + EVENTS
                    + "$CustomPacket.getThis()Ljava/lang/Object;, cast net/minecraft/network/protocol/Packet");
        }
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey());
        }
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A Forge 1.20.1 mod taken to NeoForge gets the buffer names and the event channel too")
    void neoForgeTakesTheSharedFixes(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        Path input = dir.resolve("cfix.jar");
        Files.write(input, modJar());
        Path transformed = dir.resolve("transformed.jar");
        CliHarness.transform(input, transformed, host);

        Map<String, String> found = summary(transformed);
        assertEquals("[" + BUF + ".readIdentifier()L" + ID + ";, " + BUF + ".writeIdentifier(L" + ID + ";)L" + BUF + ";]",
                found.get("buffer calls"));
        assertEquals(BUF + ".writeIdentifier(L" + ID + ";)L" + BUF + ";", found.get("nested:buffer method reference"));
        assertTrue(found.get("event channel open").startsWith(EVENTS + ".open("), found.get("event channel open"));
        assertEquals(EVENTS + ".addListener(Ljava/util/function/Consumer;)V", found.get("nested:event channel listen"));
    }

    static Map<String, String> summary(Path jar) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describe(file, "cfix/", "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "cfixlib/", "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static void describe(JarFile jar, String pkg, String prefix, Map<String, String> found) throws Exception {
        ClassNode buffers = node(jar, pkg + "Buffers");
        found.put(prefix + "buffer calls", calls(buffers, BUF).stream().sorted().toList().toString());
        found.put(prefix + "buffer method reference", handle(buffers));

        ClassNode subclass = node(jar, pkg + "FoodSetup$2");
        String base = subclass.superName;
        boolean embedded = base.startsWith(SyntheticEmbedder.PREFIX)
                && base.endsWith("/" + LegacyIngredientTypesBridge.COMPOUND);
        boolean ctor = embedded && node(jar, base).methods.stream()
                .anyMatch(m -> m.name.equals("<init>") && m.desc.equals("(Ljava/util/List;)V"));
        boolean callsIt = calls(subclass, base).contains(base + ".<init>(Ljava/util/List;)V");
        found.put(prefix + "compound subclass", embedded && ctor && callsIt
                ? "extends the embedded LegacyCompoundIngredient, whose (List) constructor its super call names"
                : "extends " + base + ", ctor " + ctor + ", super call " + callsIt);

        List<String> factories = new ArrayList<>();
        for (MethodNode method : node(jar, pkg + "Combined").methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("of")) {
                    factories.add(method.name + " -> " + owner(jar, call.owner) + ".of");
                }
            }
        }
        found.put(prefix + "factories", factories.stream().sorted().toList().toString());

        for (MethodNode method : node(jar, pkg + "Net").methods) {
            if (method.name.equals("<init>")) continue;
            List<String> steps = new ArrayList<>();
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) steps.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) steps.add("cast " + cast.desc);
            }
            found.put(prefix + "event channel " + method.name, String.join(", ", steps));
        }
    }

    /** A Retromod class embedded in the jar is named by what it was before relocation. */
    private static String owner(JarFile jar, String owner) {
        if (!owner.startsWith(SyntheticEmbedder.PREFIX)) return owner;
        String original = owner.substring(owner.indexOf("/com/retromod/") + 1);
        assertNotNull(jar.getJarEntry(owner + ".class"), owner + " is embedded");
        return "embedded " + original.substring(original.lastIndexOf('/') + 1);
    }

    private static List<String> calls(ClassNode node, String owner) {
        List<String> found = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) {
                    found.add(call.owner + "." + call.name + call.desc);
                }
            }
        }
        return found;
    }

    private static String handle(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs[1] instanceof Handle target) {
                    return target.getOwner() + "." + target.getName() + target.getDesc();
                }
            }
        }
        return "no method reference";
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        var entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return IngredientFixtures.node(jar.getInputStream(entry).readAllBytes());
    }
}
