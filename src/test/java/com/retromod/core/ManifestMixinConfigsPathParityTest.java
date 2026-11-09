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
import com.retromod.shim.forge.ForgeNeoForgeSynthetics;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Every path that writes a neoforge.mods.toml lists the manifest's mixin configs there for NeoForge 1.21.9 and newer. */
class ManifestMixinConfigsPathParityTest {

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

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({"runtime, 26.3", "transform, 26.3", "batch, 26.3", "aot, 26.3",
            "runtime, 1.21.1", "transform, 1.21.1", "aot, 1.21.1"})
    @DisplayName("A Forge mod and its bundled NeoForge library list their manifest's mixin configs once, only where the host ignores the manifest")
    void everyPathListsManifestConfigs(String path, String host) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("mixinmod.jar");
        Files.write(input, modJar());

        Map<String, String> tomls = neoForgeTomls(translate(path, host, input));

        // Through 1.21.8 Mixin reads the manifest itself, so a [[mixins]] entry would apply the config twice.
        int expected = RetromodVersion.mcVersionExceeds("1.21.9", host) ? 0 : 1;
        String where = path + " on " + host;
        assertEquals(expected, configEntries(tomls.get("mod"), "mod.mixins.json"), "the mod's own config: " + where);
        assertEquals(1, configEntries(tomls.get("mod"), "shared.mixins.json"),
                "a config the toml already lists is never listed twice: " + where);
        assertEquals(expected, configEntries(tomls.get("nested"), "lib.mixins.json"), "the bundled library's: " + where);
        assertEquals(0, configEntries(tomls.get("mod"), "citadel.mixins.json"),
                "a config another mod ships is that mod's to list, or FML stops on a duplicate: " + where);
        assertEquals(0, configEntries(tomls.get("nested"), "missing.mixins.json"),
                "a config no jar ships would stop FML as missing: " + where);
    }

    private Path translate(String path, String host, Path input) throws Exception {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        ModVersionDetector.setDefaultPreferredLoader("neoforge");
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> runtime(host, input, out);
            case "transform" -> {
                CliHarness.transform(input, out.resolve("mixinmod.jar"), host);
                yield out.resolve("mixinmod.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("mixinmod.jar"), host);
                yield out.resolve("mixinmod.jar");
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

    /** Registers what the NeoForge entry point registers on {@code host}, then runs its mod transformer. */
    private Path runtime(String host, Path input, Path out) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Set<String> types = Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        ForgeNeoForgeSynthetics.register(transformer);
        return new ForgeModTransformer(host).transformMod(input, out);
    }

    /** The neoforge.mods.toml of the translated mod and of its bundled library. */
    private static Map<String, String> neoForgeTomls(Path output) throws Exception {
        Map<String, String> tomls = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            tomls.put("mod", text(jar, "META-INF/neoforge.mods.toml"));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                JarEntry lib = jar.getJarEntry("META-INF/jarjar/lib.jar");
                assertNotNull(lib, "the bundled library is missing from " + output);
                Files.write(nested, jar.getInputStream(lib).readAllBytes());
                try (JarFile library = new JarFile(nested.toFile())) {
                    tomls.put("nested", text(library, "META-INF/neoforge.mods.toml"));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return tomls;
    }

    private static String text(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name);
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
    }

    /** How many [[mixins]] entries name {@code config}. */
    private static int configEntries(String toml, String config) {
        Matcher matcher = Pattern.compile("\\[\\[mixins]]\\s*\\n\\s*config\\s*=\\s*\"" + Pattern.quote(config) + "\"")
                .matcher(toml);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    /**
     * A Forge 1.20.1 mod whose manifest names its two configs, one already in its toml, and Citadel's, as Alex's Mobs
     * does, bundling a NeoForge 1.21.1 library whose manifest, written last, names its config and one it lacks.
     */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"mixinlib\"\nversion=\"1.0.0\"\n[[dependencies.mixinlib]]\nmodId=\"minecraft\"\n"
                + "type=\"required\"\nversionRange=\"[1.21.1]\"\n").getBytes(StandardCharsets.UTF_8));
        lib.put("lib/Thing.class", plainClass("lib/Thing"));
        lib.put("lib.mixins.json", config("lib"));
        lib.put("META-INF/MANIFEST.MF", manifest("./lib.mixins.json, missing.mixins.json"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/MANIFEST.MF", manifest("mod.mixins.json, /shared.mixins.json, citadel.mixins.json"));
        mod.put("mod.mixins.json", config("mod"));
        mod.put("shared.mixins.json", config("shared"));
        mod.put("META-INF/mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\n"
                + "modId=\"mixinmod\"\nversion=\"1.0.0\"\n[[dependencies.mixinmod]]\nmodId=\"minecraft\"\n"
                + "mandatory=true\nversionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n"
                + "[[mixins]]\nconfig=\"shared.mixins.json\"\n").getBytes(StandardCharsets.UTF_8));
        mod.put("mod/Thing.class", plainClass("mod/Thing"));
        mod.put("META-INF/jarjar/lib.jar", LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    private static byte[] config(String pkg) {
        return ("{\"required\": false, \"package\": \"" + pkg + ".mixin\", \"mixins\": []}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] manifest(String configs) {
        return ("Manifest-Version: 1.0\r\nMixinConfigs: " + configs + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] plainClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
