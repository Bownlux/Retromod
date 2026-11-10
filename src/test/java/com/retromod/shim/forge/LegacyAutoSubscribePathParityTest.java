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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static com.retromod.shim.forge.LegacyAutoSubscribeBridgeTest.LEGACY;
import static com.retromod.shim.forge.LegacyAutoSubscribeBridgeTest.OBJECT;
import static com.retromod.shim.forge.LegacyAutoSubscribeBridgeTest.enumValue;
import static com.retromod.shim.forge.LegacyAutoSubscribeBridgeTest.handler;
import static org.junit.jupiter.api.Assertions.*;

/** Every class-transform path subscribes the same subscriber classes from the @Mod constructor, outer and nested jar alike. */
class LegacyAutoSubscribePathParityTest {

    private static final String OLD_SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    private static final String HOST_EVENT = "net/minecraftforge/event/server/ServerStartedEvent";
    private static final String HELPER = "com/retromod/legacyapi/forge/eventbus/LegacyAutoSubscriber";

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
    @DisplayName("The Forge runtime, CLI batch and AOT paths subscribe the same classes from the same constructors")
    void everyPathSubscribes(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("subscribers.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("subscribers.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        Map<String, String> expected = new TreeMap<>(Map.of(
                "mod/MyMod", "subscribes [mod.Events FORGE CLIENT,DEDICATED_SERVER] through the embedded helper",
                "mod/Events", "left to the constructor",
                "nested:fixturelib/LibMod", "subscribes [fixturelib.LibEvents MOD CLIENT] through the embedded helper",
                "nested:fixturelib/LibEvents", "left to the constructor"));
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey());
        }
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Every path hands a mod-bus class whose event left the mod bus to the constructor, outer and nested jar alike")
    void everyPathRehomesMovedModBusSubscribers(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("rehome.jar");
        Files.write(input, movedEventJar());
        List<String> outer = List.of("mod/RehomeMod", "mod/ClientModEvents", "mod/SetupEvents");
        List<String> nested = List.of("fixturelib/LibMod", "fixturelib/LibTabs");

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host), outer, nested));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("rehome.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(onlyJar(batchOut), outer, nested));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot, outer, nested));

        Map<String, String> expected = new TreeMap<>(Map.of(
                "mod/RehomeMod", "subscribes [mod.ClientModEvents MOD CLIENT,DEDICATED_SERVER] through the embedded helper",
                "mod/ClientModEvents", "left to the constructor",
                "mod/SetupEvents", "still subscribed by FML",
                "nested:fixturelib/LibMod", "subscribes [fixturelib.LibTabs MOD CLIENT,DEDICATED_SERVER] through the embedded helper",
                "nested:fixturelib/LibTabs", "left to the constructor"));
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
        return summary(jar, List.of("mod/MyMod", "mod/Events"), List.of("fixturelib/LibMod", "fixturelib/LibEvents"));
    }

    /** Each list names a @Mod class first, then the subscriber classes beside it. */
    private static Map<String, String> summary(Path jar, List<String> outer, List<String> inner) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describe(file, outer.get(0), outer.subList(1, outer.size()), "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, inner.get(0), inner.subList(1, inner.size()), "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    /** The list each @Mod constructor hands to an embedded helper, and whether FML still sees the subscriber. */
    private static void describe(JarFile jar, String modClass, List<String> subscribers, String prefix,
            Map<String, String> found) throws Exception {
        List<String> lists = new java.util.ArrayList<>();
        boolean embedded = true;
        for (MethodNode method : ModConstructorHooksTest.node(read(jar, modClass)).methods) {
            if (!method.name.equals("<init>")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.endsWith(HELPER) && call.name.equals("subscribe")) {
                    lists.add((String) ((LdcInsnNode) call.getPrevious()).cst);
                    embedded &= call.owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(call.owner + ".class") != null;
                }
            }
        }
        found.put(prefix + modClass, "subscribes " + lists + (embedded ? " through the embedded helper" : " through a missing helper"));
        for (String subscriber : subscribers) {
            found.put(prefix + subscriber, LegacyAutoSubscribeBridgeTest.hasAutoSubscriber(read(jar, subscriber))
                    ? "still subscribed by FML" : "left to the constructor");
        }
    }

    private static byte[] read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return jar.getInputStream(entry).readAllBytes();
    }

    static Path onlyJar(Path dir) throws Exception {
        try (var jars = Files.list(dir)) {
            List<Path> all = jars.filter(path -> path.toString().endsWith(".jar")).toList();
            assertEquals(1, all.size(), "the batch wrote one jar: " + all);
            return all.get(0);
        }
    }

    /**
     * A Forge 1.20.1 mod whose subscriber has a listener the event-base pass made legacy beside a host one,
     * and a nested library mod with a client-only mod-bus subscriber.
     */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", toml("fixturelib"));
        lib.put("fixturelib/LibMod.class", ModConstructorHooksTest.modClass("fixturelib/LibMod", "fixturelib", OBJECT, (w, o) -> { }));
        lib.put("fixturelib/LibEvent.class", LegacyEventBaseAdapterTest.eventClass("fixturelib/LibEvent",
                LegacyEventBaseAdapter.EVENT, null));
        lib.put("fixturelib/LibEvents.class", LegacyAutoSubscribeBridgeTest.subscriber("fixturelib/LibEvents",
                List.of("bus", enumValue("Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus;", "MOD"),
                        "value", List.of((Object) enumValue("Lnet/minecraftforge/api/distmarker/Dist;", "CLIENT"))),
                handler("onLib", "fixturelib/LibEvent", LEGACY)));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", toml("automod"));
        mod.put("mod/MyMod.class", ModConstructorHooksTest.modClass("mod/MyMod", "automod", OBJECT, (w, o) -> { }));
        mod.put("mod/MyEvent.class", LegacyEventBaseAdapterTest.eventClass("mod/MyEvent", LegacyEventBaseAdapter.EVENT, null));
        mod.put("mod/Events.class", LegacyAutoSubscribeBridgeTest.subscriber("mod/Events", List.of(),
                handler("onMine", "mod/MyEvent", LEGACY), handler("onStart", HOST_EVENT, OLD_SUBSCRIBE)));
        mod.put("META-INF/jarjar/lib.jar", jar(lib));
        return jar(mod);
    }

    /**
     * A Forge 1.20.1 mod with a mod-bus subscriber on RegisterKeyMappingsEvent, which Forge 26.2 moved to the game
     * group, beside one with only lifecycle events, and a nested library with a creative-tab subscriber.
     */
    private static byte[] movedEventJar() throws Exception {
        String old = LegacyModBusEventsTest.OLD;
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", toml("fixturelib"));
        lib.put("fixturelib/LibMod.class", ModConstructorHooksTest.modClass("fixturelib/LibMod", "fixturelib", OBJECT, (w, o) -> { }));
        lib.put("fixturelib/LibTabs.class", LegacyAutoSubscribeBridgeTest.subscriber("fixturelib/LibTabs",
                LegacyModBusEventsTest.MOD_BUS, handler("onTabs", LegacyModBusEventsTest.TABS, old),
                handler("onSetup", LegacyModBusEventsTest.COMMON_SETUP, old)));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", toml("rehomemod"));
        mod.put("mod/RehomeMod.class", ModConstructorHooksTest.modClass("mod/RehomeMod", "rehomemod", OBJECT, (w, o) -> { }));
        mod.put("mod/ClientModEvents.class", LegacyAutoSubscribeBridgeTest.subscriber("mod/ClientModEvents",
                LegacyModBusEventsTest.MOD_BUS, handler("onKeys", LegacyModBusEventsTest.KEYS, old),
                handler("onClientSetup", LegacyModBusEventsTest.CLIENT_SETUP, old)));
        mod.put("mod/SetupEvents.class", LegacyAutoSubscribeBridgeTest.subscriber("mod/SetupEvents",
                LegacyModBusEventsTest.MOD_BUS, handler("onCommonSetup", LegacyModBusEventsTest.COMMON_SETUP, old),
                handler("onClientSetup", LegacyModBusEventsTest.CLIENT_SETUP, old)));
        mod.put("META-INF/jarjar/lib.jar", jar(lib));
        return jar(mod);
    }

    static byte[] toml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
