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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static com.retromod.shim.forge.NbtHostFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.CHECKCAST;

/** Every class-transform path bridges old providers, handler calls and item hooks the same way, outer and nested jar alike. */
class LegacyCapabilityDataPathParityTest {

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
    @DisplayName("The Forge runtime, CLI batch and AOT paths bridge the provider, the handler call and the item hook alike")
    void everyPathAgrees(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("capdata.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("capdata.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        Map<String, String> expected = new TreeMap<>();
        for (String prefix : new String[]{"", "nested:"}) {
            expected.put(prefix + "mod/Counter", "implements the embedded LegacyCapabilitySerializable,"
                    + " with its own registry-taking save and load");
            expected.put(prefix + "mod/User", "calls ItemStackHandler.serializeNBT with the embedded context's registries");
            expected.put(prefix + "mod/Pouch", "answers getCapabilityProvider from its own initCapabilities");
        }
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey());
        }
    }

    /** What RetromodForge registers on the host, then one runtime transform. */
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

    private static void describe(JarFile jar, String prefix, Map<String, String> found) throws Exception {
        ClassNode counter = node(classBytes(jar, "mod/Counter"));
        String iface = counter.interfaces.size() == 1 ? counter.interfaces.get(0) : "";
        boolean embedded = iface.startsWith(SyntheticEmbedder.PREFIX) && iface.endsWith(LegacyNbtSerializableBridge.LEGACY_CAPABILITY)
                && jar.getJarEntry(iface + ".class") != null;
        boolean bridged = has(counter, "serializeNBT", "(" + L_PROVIDER + ")" + L_TAG)
                && has(counter, "deserializeNBT", "(" + L_PROVIDER + L_TAG + ")V");
        found.put(prefix + "mod/Counter", (embedded ? "implements the embedded LegacyCapabilitySerializable" : "implements " + counter.interfaces)
                + (bridged ? ", with its own registry-taking save and load" : ", without the registry-taking pair"));

        String context = null;
        String save = null;
        for (AbstractInsnNode insn : method(node(classBytes(jar, "mod/User")), "run").instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.name.equals("provider")) context = call.owner;
            if (call.owner.equals(ITEM_HANDLER)) save = call.name + call.desc;
        }
        boolean fromContext = context != null && context.startsWith(SyntheticEmbedder.PREFIX)
                && context.endsWith(LegacyNbtSerializableBridge.CONTEXT) && jar.getJarEntry(context + ".class") != null;
        found.put(prefix + "mod/User", ("serializeNBT(" + L_PROVIDER + ")" + L_COMPOUND).equals(save) && fromContext
                ? "calls ItemStackHandler.serializeNBT with the embedded context's registries" : "calls " + save + " after " + context);

        ClassNode pouch = node(classBytes(jar, "mod/Pouch"));
        boolean hooked = pouch.methods.stream().anyMatch(m -> m.name.equals("getCapabilityProvider")
                && m.desc.equals(LegacyItemCapabilityHookAdapter.HOOK_DESC) && callsOwnInit(m, pouch.name));
        found.put(prefix + "mod/Pouch", hooked ? "answers getCapabilityProvider from its own initCapabilities" : "has no hook");
    }

    private static boolean callsOwnInit(MethodNode hook, String owner) {
        for (AbstractInsnNode insn : hook.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals("initCapabilities")) return true;
        }
        return false;
    }

    private static boolean has(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name));
    }

    private static byte[] classBytes(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return jar.getInputStream(entry).readAllBytes();
    }

    /** A Forge 1.20.1 mod with an old provider, an old handler call and an old item hook, and a nested library with the same. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("fixturelib"));
        addClasses(lib);
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("capdata"));
        addClasses(mod);
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static void addClasses(Map<String, byte[]> jar) {
        jar.put("mod/Counter.class", oldProvider("mod/Counter", "java/lang/Object", CAP_SERIALIZABLE));
        jar.put("mod/User.class", caller("mod/User", "run", "(Ljava/lang/Object;)V", m -> {
            m.visitVarInsn(ALOAD, 0);
            m.visitTypeInsn(CHECKCAST, ITEM_HANDLER);
            m.visitMethodInsn(INVOKEVIRTUAL, ITEM_HANDLER, "serializeNBT", "()" + L_COMPOUND, false);
            m.visitInsn(POP);
            m.visitInsn(RETURN);
        }));
        jar.put("mod/Pouch.class", LegacyItemCapabilityHookAdapterTest.item("mod/Pouch", ITEM, false));
    }
}
