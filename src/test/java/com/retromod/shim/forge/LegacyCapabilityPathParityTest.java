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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
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

import static com.retromod.shim.forge.CapabilityHostFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;

/** Every class-transform path splits attach listeners and wires the register event the same way, outer and nested jar alike. */
class LegacyCapabilityPathParityTest {

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
    @DisplayName("The Forge runtime, CLI batch and AOT paths split the listener, keep the lone register handler for FML, and start the registration")
    void everyPathAgrees(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("caps.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("caps.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        Map<String, String> expected = new TreeMap<>();
        for (String prefix : List.of("", "nested:")) {
            String modClass = prefix.isEmpty() ? "mod/CapMod" : "fixturelib/LibMod";
            expected.put(prefix + modClass, "starts the registration through the embedded helper");
            expected.put(prefix + "mod/Attacher", "[retromod$onEntity$Entities priority 64] beside an unsubscribed onEntity");
            expected.put(prefix + "mod/CapRegistrar", "still subscribed by FML, with no companion, and takes the embedded event");
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
            describe(file, "mod/CapMod", "", found);
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

    private static void describe(JarFile jar, String modClass, String prefix, Map<String, String> found) throws Exception {
        boolean starts = false;
        for (MethodNode method : node(jar, modClass).methods) {
            if (!method.name.equals("<init>")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.endsWith(LegacyCapabilityEventBridge.REGISTRATION)
                        && call.name.equals("watch")) {
                    starts = call.owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(call.owner + ".class") != null;
                }
            }
        }
        found.put(prefix + modClass, starts ? "starts the registration through the embedded helper" : "does not start it");

        ClassNode attacher = node(jar, "mod/Attacher");
        List<String> listeners = new ArrayList<>();
        boolean originalSubscribed = false;
        for (MethodNode method : attacher.methods) {
            AnnotationNode subscribe = subscribe(method);
            if (method.name.equals("onEntity")) originalSubscribed = subscribe != null;
            if (method.name.startsWith("retromod$") && subscribe != null) {
                listeners.add(method.name + " priority " + ForgeSubscribeAnnotations.priorityOf(subscribe));
            }
        }
        found.put(prefix + "mod/Attacher", listeners + (originalSubscribed ? " beside a subscribed onEntity"
                : " beside an unsubscribed onEntity"));

        ClassNode registrar = node(jar, "mod/CapRegistrar");
        String event = Type.getArgumentTypes(registrar.methods.stream().filter(m -> m.name.equals("onRegister"))
                .findFirst().orElseThrow().desc)[0].getInternalName();
        boolean embedded = event.startsWith(SyntheticEmbedder.PREFIX) && event.endsWith(LegacyCapabilityEventBridge.STAND_IN)
                && jar.getJarEntry(event + ".class") != null;
        boolean companion = jar.getJarEntry(ForgeEventBusSynthetics.companionName("mod/CapRegistrar") + ".class") != null;
        found.put(prefix + "mod/CapRegistrar", (LegacyAutoSubscribeBridgeTest.hasAutoSubscriber(classBytes(jar, "mod/CapRegistrar"))
                ? "still subscribed by FML" : "taken from FML") + (companion ? ", with a companion" : ", with no companion")
                + (embedded ? ", and takes the embedded event" : ", and takes " + event));
    }

    private static AnnotationNode subscribe(MethodNode method) {
        if (method.visibleAnnotations == null) return null;
        return method.visibleAnnotations.stream().filter(a -> a.desc.equals(ForgeSubscribeAnnotations.SUBSCRIBE7))
                .findFirst().orElse(null);
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        return ModConstructorHooksTest.node(classBytes(jar, name));
    }

    private static byte[] classBytes(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return jar.getInputStream(entry).readAllBytes();
    }

    /** A Forge 1.20.1 mod with an attach listener and a mod-bus class whose one handler registers capabilities, nested too. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("fixturelib"));
        addClasses(lib, "fixturelib/LibMod", "fixturelib");
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("capmod"));
        addClasses(mod, "mod/CapMod", "capmod");
        mod.put("META-INF/jarjar/lib.jar", LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static void addClasses(Map<String, byte[]> jar, String modClass, String modId) {
        jar.put(modClass + ".class", ModConstructorHooksTest.modClass(modClass, modId, LegacyAutoSubscribeBridgeTest.OBJECT,
                (writer, owner) -> { }));
        jar.put("mod/Attacher.class", LegacyAttachListenerSplitTest.attacher(ACC_PUBLIC,
                new LegacyAttachListenerSplitTest.Handler("onEntity", ACC_PUBLIC, "L" + ENTITY + ";", "HIGH", "entity", null)));
        jar.put("mod/CapRegistrar.class", LegacyAutoSubscribeBridgeTest.subscriber("mod/CapRegistrar",
                LegacyModBusEventsTest.MOD_BUS, LegacyAutoSubscribeBridgeTest.handler("onRegister", REGISTER_EVENT, OLD_SUBSCRIBE)));
    }
}
