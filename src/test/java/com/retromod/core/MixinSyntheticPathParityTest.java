/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

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
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Every NeoForge write path leaves out Mixin's synthetic package, which NeoForge 26.3 refuses as a split package at boot. */
class MixinSyntheticPathParityTest {

    private static final String DUMMY = "org/spongepowered/asm/synthetic/args/Dummy.class";

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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"runtime", "transform", "batch", "aot"})
    @DisplayName("The mod and its nested library lose the synthetic package and keep their own classes")
    void everyPathDropsTheSyntheticPackage(String path) throws Exception {
        Path output = translate(path);

        Map<String, List<String>> names = entryNames(output);
        for (Map.Entry<String, List<String>> jar : names.entrySet()) {
            assertTrue(jar.getValue().stream().noneMatch(name -> name.startsWith("org/spongepowered/asm/synthetic/")),
                    jar.getKey() + " (" + path + ") still ships the package: " + jar.getValue());
            assertTrue(jar.getValue().stream().anyMatch(name -> name.endsWith("/Main.class")),
                    jar.getKey() + " (" + path + ") lost its own class: " + jar.getValue());
        }
    }

    @Test
    @DisplayName("Only a NeoForge target from 1.20.2 leaves the package out")
    void onlyNeoForgeHostsDropIt() {
        McReflect.setForceNeoForge(true);
        assertTrue(ForgeModTransformer.dropsMixinSyntheticEntry(DUMMY, "26.3"));
        assertFalse(ForgeModTransformer.dropsMixinSyntheticEntry(DUMMY, "1.20.1"), "NeoForge 1.20.1 is a Forge fork");
        assertFalse(ForgeModTransformer.dropsMixinSyntheticEntry("org/spongepowered/asm/mixin/Mixin.class", "26.3"));
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        assertFalse(ForgeModTransformer.dropsMixinSyntheticEntry(DUMMY, "26.3"), "Forge keeps the placeholder");
    }

    private Path translate(String path) throws Exception {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        ModVersionDetector.setDefaultPreferredLoader("neoforge");
        Path input = Files.createDirectories(dir.resolve("in")).resolve("dummymod.jar");
        Files.write(input, modJar());
        Path out = Files.createDirectories(dir.resolve("out"));
        return switch (path) {
            case "runtime" -> runtime(input, out);
            case "transform" -> {
                CliHarness.transform(input, out.resolve("dummymod.jar"), "26.3");
                yield out.resolve("dummymod.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("dummymod.jar"), "26.3");
                yield out.resolve("dummymod.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, "26.3");
                try (var jars = Files.list(out)) {
                    yield jars.filter(jar -> jar.toString().endsWith(".jar")).findFirst().orElseThrow();
                }
            }
        };
    }

    /** What the NeoForge entry point registers on 26.3, then its mod transformer. */
    private Path runtime(Path input, Path out) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        Set<String> loaders = Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (loaders.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, "26.3")) {
                ShimRegistration.register(shim, transformer);
            }
        }
        return new ForgeModTransformer("26.3").transformMod(input, out);
    }

    /** A NeoForge 1.21.1 mod shipping the placeholder, with a nested library that ships it too, as Blueprint does. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/neoforge.mods.toml", toml("dummylib"));
        lib.put("dummylib/Main.class", emptyClass("dummylib/Main"));
        lib.put(DUMMY, emptyClass("org/spongepowered/asm/synthetic/args/Dummy"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/neoforge.mods.toml", toml("dummymod"));
        mod.put("dummymod/Main.class", emptyClass("dummymod/Main"));
        mod.put("org/spongepowered/asm/synthetic/args/", new byte[0]);
        mod.put(DUMMY, emptyClass("org/spongepowered/asm/synthetic/args/Dummy"));
        mod.put("META-INF/jarjar/lib.jar", LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    private static byte[] emptyClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] toml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\ntype=\"required\"\n"
                + "versionRange=\"[1.21.1,1.21.2)\"\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Entry names of the output jar and of its nested library. */
    private static Map<String, List<String>> entryNames(Path output) throws Exception {
        Map<String, List<String>> found = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            List<String> names = new ArrayList<>();
            for (JarEntry entry : Collections.list(jar.entries())) names.add(entry.getName());
            found.put("mod", names);
            JarEntry nested = jar.getJarEntry("META-INF/jarjar/lib.jar");
            assertNotNull(nested, "the nested library is missing from " + output);
            List<String> nestedNames = new ArrayList<>();
            try (ZipInputStream in = new ZipInputStream(jar.getInputStream(nested))) {
                for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) nestedNames.add(entry.getName());
            }
            found.put("nested", nestedNames);
        }
        return found;
    }
}
