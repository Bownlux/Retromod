/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static com.retromod.core.LoaderTypeInterfaceCallTest.MOD_CONFIG_EVENT;
import static com.retromod.core.LoaderTypeInterfaceCallTest.assertCall;
import static com.retromod.core.LoaderTypeInterfaceCallTest.assertHandle;
import static org.junit.jupiter.api.Assertions.*;

/** Every class-transform path gives a Forge 1.20.1 mod's ModConfigEvent calls the form the target host needs, in the mod and its nested library. */
class LoaderTypeInterfacePathParityTest {

    private static final String NEO_MOD_CONFIG_EVENT = "net/neoforged/fml/event/config/ModConfigEvent";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} {1} on {2}")
    @CsvSource({"forge, runtime, 26.2", "forge, runtime, 26.3", "forge, transform, 26.2", "forge, transform, 26.3",
            "forge, batch, 26.2", "forge, aot, 26.2", "forge, aot, 26.3", "forge, runtime, 1.21.1",
            "forge, transform, 1.21.1", "neoforge, runtime, 26.2", "neoforge, transform, 26.3"})
    @DisplayName("each path calls ModConfigEvent as the host's kind of type, in the mod and its nested library")
    void everyPathMatchesTheHostKind(String loader, String path, String host) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("lootr.jar");
        Files.write(input, modJar());

        Path output = translate(loader, path, host, input);

        // Forge 26.2 made ModConfigEvent an interface; NeoForge kept its renamed class, and older Forge its class.
        boolean forgeInterface = loader.equals("forge") && RetromodVersion.compareMcVersions(host, "26.2") >= 0;
        String owner = loader.equals("neoforge") ? NEO_MOD_CONFIG_EVENT : MOD_CONFIG_EVENT;
        for (Map.Entry<String, ClassNode> jar : listeners(output).entrySet()) {
            ClassNode node = jar.getValue();
            node.name = jar.getKey() + " (" + loader + " " + path + " on " + host + ")";
            assertCall(node, owner, forgeInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, forgeInterface);
            assertHandle(node, owner, forgeInterface ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL, forgeInterface);
        }
    }

    private Path translate(String loader, String path, String host, Path input) throws Exception {
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader);
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> runtime(loader, host, input, out);
            case "transform" -> {
                CliHarness.transform(input, out.resolve("lootr.jar"), host);
                yield out.resolve("lootr.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("lootr.jar"), host);
                yield out.resolve("lootr.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                try (var jars = Files.list(out)) {
                    List<Path> all = jars.filter(jar -> jar.toString().endsWith(".jar")).toList();
                    assertEquals(1, all.size(), "the batch wrote one jar: " + all);
                    yield all.get(0);
                }
            }
        };
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** Registers what the Forge or NeoForge entry point registers on {@code host}, then runs its mod transformer. */
    private Path runtime(String loader, String host, Path input, Path out) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Set<String> types = loader.equals("forge") ? Set.of("forge", "common") : Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        return new ForgeModTransformer(host).transformMod(input, out);
    }

    /** The listener class of the mod and of its nested library, as the output jar holds them. */
    private static Map<String, ClassNode> listeners(Path output) throws Exception {
        Map<String, ClassNode> found = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            found.put("mod", read(jar.getInputStream(entry(jar, "mod/ConfigManager.class")).readAllBytes()));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, jar.getInputStream(entry(jar, "META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    found.put("nested", read(lib.getInputStream(entry(lib, "lib/ConfigManager.class")).readAllBytes()));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static JarEntry entry(JarFile jar, String name) {
        JarEntry entry = jar.getJarEntry(name);
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return entry;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** A Forge 1.20.1 mod whose config manager calls ModConfigEvent.getConfig(), as Lootr's does, with a nested library doing the same. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", modsToml("configlib"));
        lib.put("lib/ConfigManager.class", LoaderTypeInterfaceCallTest.listener("lib/ConfigManager", MOD_CONFIG_EVENT));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", modsToml("lootr"));
        mod.put("mod/ConfigManager.class", LoaderTypeInterfaceCallTest.listener("mod/ConfigManager", MOD_CONFIG_EVENT));
        mod.put("META-INF/jarjar/lib.jar", LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    private static byte[] modsToml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\n"
                + "mandatory=true\nversionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
