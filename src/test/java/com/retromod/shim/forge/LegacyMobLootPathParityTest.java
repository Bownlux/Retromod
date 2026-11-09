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
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.objectweb.asm.Opcodes.*;

/** Every transform path gives a Forge 1.20.1 mob that names its own death table the host-shaped member that drops it. */
class LegacyMobLootPathParityTest {

    private static final String NESTED = "META-INF/jarjar/lib.jar";
    private static final String RL = "net/minecraft/resources/ResourceLocation";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void reset() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(false);
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
    @DisplayName("Forge: the runtime, CLI batch, CLI transform and AOT paths, and a nested jar, adapt the mob")
    void forge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceForge(true);
        assertEveryPathAgrees(host, dir);
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"1.21.1", "1.21.4", "26.1", "26.3"})
    @DisplayName("NeoForge: every path adapts the mob in the shape its host takes")
    void neoForge(String host, @TempDir Path dir) throws Exception {
        McReflect.setForceNeoForge(true);
        assertEveryPathAgrees(host, dir);
    }

    private void assertEveryPathAgrees(String host, Path dir) throws Exception {
        Path input = dir.resolve("mobs.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("runtime", summary(runtime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("mobs.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut)));
        Path transformed = dir.resolve("transformed.jar");
        CliHarness.transform(input, transformed, host);
        outputs.put("CLI transform", summary(transformed));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        Map<String, String> expected = new TreeMap<>();
        for (String prefix : List.of("", "nested:")) {
            expected.put(prefix + "mob", expectedMob(host));
            expected.put(prefix + "base call", "embedded LegacyMobLootTables.mobDefaultLootTable");
        }
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), host + ", " + output.getKey());
        }
    }

    // 1.21.1 asks the mob for a key; 1.21.2 to 1.21.8 get Retromod's own drop; 1.21.9 on take the table into the host's drop.
    private static String expectedMob(String host) {
        if (RetromodVersion.compareMcVersions(host, "1.21.2") < 0) {
            return "declares getDefaultLootTable()ResourceKey calling embedded LegacyMobLootTables.defaultLootTableKey";
        }
        if (RetromodVersion.compareMcVersions(host, "1.21.9") < 0) {
            return "declares dropFromLootTable(ServerLevel, DamageSource, boolean) calling embedded LegacyEntityLootDrops.drop,"
                    + " embedded LegacyMobLootTables.defaultLootTableKey";
        }
        return "declares dropFromLootTable(ServerLevel, DamageSource, boolean) calling embedded LegacyMobLootTables.defaultLootTableKey";
    }

    /** What the loader's entry point registers on the host, mappings and shims, then one runtime transform. */
    private Path runtime(Path input, Path dir, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Path out = Files.createDirectory(dir.resolve("runtime"));
        // RetromodForge and RetromodNeoForge decode the mod's SRG names on Mojang-named hosts.
        SrgToMojangMapper.getInstance().applyTo(transformer);
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            Set<String> loaders = McReflect.isNeoForge() ? Set.of("neoforge", "forge", "common") : Set.of("forge", "common");
            if (loaders.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        Path written = new ForgeModTransformer(host).transformMod(input, out);
        assertNotNull(written, "the runtime wrote a jar");
        return written;
    }

    private static Map<String, String> summary(Path jar) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describe(file, "mod/", "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry(NESTED)).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "fixturelib/", "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    // Which host-shaped member the mob gained and what it calls, and what its super.getDefaultLootTable() became.
    private static void describe(JarFile jar, String pkg, String prefix, Map<String, String> found) throws Exception {
        ClassNode crab = node(jar, pkg + "Crab");
        String member = "nothing";
        for (MethodNode method : crab.methods) {
            if (method.name.equals("getDefaultLootTable") && method.desc.equals("()Lnet/minecraft/resources/ResourceKey;")) {
                member = "getDefaultLootTable()ResourceKey calling " + String.join(", ", embeddedCalls(jar, method));
            } else if (method.name.equals("dropFromLootTable") && method.desc.startsWith("(Lnet/minecraft/server/level/ServerLevel;")) {
                member = "dropFromLootTable(ServerLevel, DamageSource, boolean) calling " + String.join(", ", embeddedCalls(jar, method));
            }
        }
        found.put(prefix + "mob", "declares " + member);
        MethodNode old = crab.methods.stream().filter(m -> m.name.equals("getDefaultLootTable") && m.desc.endsWith(";")
                && !m.desc.contains("ResourceKey")).findFirst().orElseThrow();
        found.put(prefix + "base call", String.join(", ", embeddedCalls(jar, old)));
    }

    private static Set<String> embeddedCalls(JarFile jar, MethodNode method) {
        Set<String> calls = new TreeSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.startsWith(SyntheticEmbedder.PREFIX)
                    && jar.getJarEntry(call.owner + ".class") != null) {
                calls.add("embedded " + call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
            }
        }
        return calls;
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return ModConstructorHooksTest.node(jar.getInputStream(entry).readAllBytes());
    }

    /** A Forge 1.20.1 mod with the mob, and a nested library mod with the same. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = files("fixturelib", "fixturelib/", null);
        Map<String, byte[]> mod = files("mobsmod", "mod/", NESTED);
        mod.put(NESTED, LegacyAutoSubscribePathParityTest.jar(lib));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static Map<String, byte[]> files(String modId, String pkg, String nestedJar) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml(modId));
        files.put(pkg + "Crab.class", crab(pkg + "Crab"));
        return files;
    }

    // As Alex's Mobs' mobs: getDefaultLootTable() (SRG m_7582_) returning the type's table through super when it has none of its own.
    private static byte[] crab(String name) {
        String monster = "net/minecraft/world/entity/monster/Monster";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, monster, null);
        MethodVisitor table = cw.visitMethod(ACC_PROTECTED, "m_7582_", "()L" + RL + ";", null, null);
        table.visitCode();
        table.visitVarInsn(ALOAD, 0);
        table.visitMethodInsn(INVOKESPECIAL, monster, "m_7582_", "()L" + RL + ";", false);
        table.visitInsn(ARETURN);
        table.visitMaxs(0, 0);
        table.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
