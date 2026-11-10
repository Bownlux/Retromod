/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.testsupport.StaticItemStackMixinFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.retromod.testsupport.StaticItemStackMixinFixture.MIXIN;
import static com.retromod.testsupport.StaticItemStackMixinFixture.MIXIN_CONFIG;
import static org.junit.jupiter.api.Assertions.*;

/** Every class-transform path moves a Mixin's static ItemStack out of its target's class initializer. */
class StaticItemStackMixinPathTest {

    private static final String HOST = "26.2";
    private static final String NESTED_MIXIN = "fixture/lib/NestedInventoryMixin";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedHost;
    private Field resolverField;
    private FuzzyMethodResolver savedResolver;

    @BeforeEach
    void useHost() throws Exception {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = HOST;
        // No indexed jar, so the host gate answers from the 26.2 target.
        resolverField = RetromodTransformer.class.getDeclaredField("fuzzyResolver");
        resolverField.setAccessible(true);
        savedResolver = (FuzzyMethodResolver) resolverField.get(transformer);
        resolverField.set(transformer, null);
    }

    @AfterEach
    void restore() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = savedHost;
        transformer.setModSourceVersion(null);
        resolverField.set(transformer, savedResolver);
    }

    @Test
    @DisplayName("Forge runtime: the mod's Mixin and a bundled library's Mixin are both repaired")
    void forgeRuntime(@TempDir Path tmp) throws Exception {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("META-INF/mods.toml", StaticItemStackMixinFixture.forgeToml("[1.20.1,1.21)"));
        entries.put(MIXIN + ".class", StaticItemStackMixinFixture.mixinClass(MIXIN));
        entries.put("META-INF/jarjar/lib.jar", nestedLibrary(tmp, false));
        Path source = StaticItemStackMixinFixture.writeJar(tmp.resolve("carry-forge.jar"), entries);

        Path output = new ForgeModTransformer(HOST).transformMod(source, Files.createDirectory(tmp.resolve("out")));

        assertNotNull(output);
        assertDeferred(entry(output, MIXIN + ".class"), MIXIN);
        assertDeferred(nestedEntry(output, "META-INF/jarjar/lib.jar", NESTED_MIXIN + ".class"), NESTED_MIXIN);
    }

    @Test
    @DisplayName("NeoForge runtime: a NeoForge mod's Mixin is repaired")
    void neoForgeRuntime(@TempDir Path tmp) throws Exception {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("META-INF/neoforge.mods.toml", StaticItemStackMixinFixture.forgeToml("[1.21.1,1.22)"));
        entries.put(MIXIN + ".class", StaticItemStackMixinFixture.mixinClass(MIXIN));
        Path source = StaticItemStackMixinFixture.writeJar(tmp.resolve("carry-neoforge.jar"), entries);

        Path output = new ForgeModTransformer(HOST).transformMod(source, Files.createDirectory(tmp.resolve("out")));

        assertNotNull(output);
        assertDeferred(entry(output, MIXIN + ".class"), MIXIN);
    }

    @Test
    @DisplayName("Forge runtime: a Mixin from a mod built for 26.1 keeps its initializer")
    void forgeRuntimeLeavesCurrentModsAlone(@TempDir Path tmp) throws Exception {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("META-INF/mods.toml", StaticItemStackMixinFixture.forgeToml("[26.1,)"));
        entries.put(MIXIN + ".class", StaticItemStackMixinFixture.mixinClass(MIXIN));
        Path source = StaticItemStackMixinFixture.writeJar(tmp.resolve("carry-current.jar"), entries);

        Path output = new ForgeModTransformer(HOST).transformMod(source, Files.createDirectory(tmp.resolve("out")));

        byte[] mixin = output == null ? StaticItemStackMixinFixture.mixinClass(MIXIN) : entry(output, MIXIN + ".class");
        assertTrue(read(mixin).methods.stream().anyMatch(m -> m.name.equals("<clinit>")));
    }

    @Test
    @DisplayName("Fabric runtime: the mod's Mixin and a bundled library's Mixin are both repaired")
    void fabricRuntime(@TempDir Path tmp) throws Exception {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("fabric.mod.json", StaticItemStackMixinFixture.fabricJson("1.20.1"));
        entries.put(MIXIN_CONFIG, StaticItemStackMixinFixture.mixinConfig(MIXIN));
        entries.put(MIXIN + ".class", StaticItemStackMixinFixture.mixinClass(MIXIN));
        entries.put("META-INF/jars/lib.jar", nestedLibrary(tmp, true));
        Path source = StaticItemStackMixinFixture.writeJar(tmp.resolve("carry-fabric.jar"), entries);

        Path output = new FabricModTransformer(HOST).transformMod(source, Files.createDirectory(tmp.resolve("out")));

        assertNotNull(output);
        assertDeferred(entry(output, MIXIN + ".class"), MIXIN);
        assertDeferred(nestedEntry(output, "META-INF/jars/lib.jar", NESTED_MIXIN + ".class"), NESTED_MIXIN);
    }

    @Test
    @DisplayName("CLI: the offline class loop repairs the Mixin")
    void cli(@TempDir Path tmp) throws Exception {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("META-INF/mods.toml", StaticItemStackMixinFixture.forgeToml("[1.20.1,1.21)"));
        entries.put(MIXIN + ".class", StaticItemStackMixinFixture.mixinClass(MIXIN));
        Path source = StaticItemStackMixinFixture.writeJar(tmp.resolve("carry-cli.jar"), entries);
        Path output = tmp.resolve("carry-cli-out.jar");

        Field cliTarget = com.retromod.cli.RetromodCli.class.getDeclaredField("TARGET_MC_VERSION");
        cliTarget.setAccessible(true);
        Object savedCliTarget = cliTarget.get(null);
        try {
            cliTarget.set(null, HOST);
            // transformCommand sets the source version before it calls transformJar.
            transformer.setModSourceVersion("[1.20.1,1.21)");
            Method transformJar = com.retromod.cli.RetromodCli.class.getDeclaredMethod("transformJar",
                    Path.class, Path.class, RetromodTransformer.class, com.retromod.embedder.ModVersionInfo.class);
            transformJar.setAccessible(true);
            transformJar.invoke(null, source, output, transformer, new ModVersionDetector().detectVersion(source));
        } finally {
            cliTarget.set(null, savedCliTarget);
        }

        assertDeferred(entry(output, MIXIN + ".class"), MIXIN);
    }

    @Test
    @DisplayName("AOT and the background engine run the same post-remap repair")
    void aotAndHybrid() throws Exception {
        transformer.setModSourceVersion("1.20.1");
        MixinCompatibilityTransformer mixins = new MixinCompatibilityTransformer(transformer);
        byte[] original = StaticItemStackMixinFixture.mixinClass(MIXIN);

        Class<?> aot = Class.forName("com.retromod.aot.AotMixinRepair");
        Method apply = aot.getDeclaredMethod("apply", MixinCompatibilityTransformer.class, byte[].class, String.class);
        apply.setAccessible(true);
        assertDeferred((byte[]) apply.invoke(null, mixins, original, MIXIN.replace('/', '.')), MIXIN);

        assertDeferred(HybridTransformationEngine.transformWithMixinRepairs(transformer, mixins, original, MIXIN),
                MIXIN);
    }

    /** A library jar with its own Mixin; a Fabric library also carries its own mixin config. */
    private static byte[] nestedLibrary(Path tmp, boolean fabric) throws Exception {
        Map<String, Object> entries = new LinkedHashMap<>();
        if (fabric) {
            entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"carry_lib\",\"version\":\"1.0.0\","
                    + "\"mixins\":[\"lib.mixins.json\"]}");
            entries.put("lib.mixins.json", StaticItemStackMixinFixture.mixinConfig(NESTED_MIXIN));
        }
        entries.put(NESTED_MIXIN + ".class", StaticItemStackMixinFixture.mixinClass(NESTED_MIXIN));
        Path jar = StaticItemStackMixinFixture.writeJar(
                tmp.resolve(fabric ? "fabric-lib.jar" : "forge-lib.jar"), entries);
        return Files.readAllBytes(jar);
    }

    private static void assertDeferred(byte[] mixin, String name) {
        ClassNode node = read(mixin);
        assertTrue(node.methods.stream().noneMatch(m -> m.name.equals("<clinit>")),
                name + " still builds its ItemStack in the target's class initializer");
        String init = "retromod$initStatics$" + name.replace('/', '_');
        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals(init)), name + " has no guarded initializer");
    }

    private static byte[] entry(Path jar, String name) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile())) {
            ZipEntry entry = archive.getEntry(name);
            assertNotNull(entry, name + " is missing from " + jar.getFileName());
            try (var input = archive.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static byte[] nestedEntry(Path jar, String nested, String name) throws Exception {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(entry(jar, nested)))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
                if (entry.getName().equals(name)) return input.readAllBytes();
            }
        }
        throw new AssertionError(name + " is missing from " + nested);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
