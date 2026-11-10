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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Every path gives a mod built before 1.20.5, and its nested library, loot table ids from BuiltInLootTables, and leaves a 1.21.1 mod's keys alone. */
class LootTableKeyPathParityTest {

    private static final String BUILT_IN = "net/minecraft/world/level/storage/loot/BuiltInLootTables";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String HELPER = "LegacyLootTableIds";

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
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} {1} on {2}")
    @CsvSource({"forge, runtime, 26.2", "forge, runtime, 26.3", "forge, transform, 26.2", "forge, transform, 26.3",
            "forge, batch, 26.2", "forge, aot, 26.2", "forge, aot, 26.3", "neoforge, runtime, 1.21.1",
            "neoforge, runtime, 26.3", "neoforge, transform, 1.21.1", "neoforge, aot, 26.3",
            "fabric, runtime, 26.2", "fabric, runtime, 26.3", "fabric, transform, 26.3", "fabric, aot, 26.2"})
    @DisplayName("a 1.20.1 mod and its nested library read loot table constants and all() as ids")
    void everyPathBridgesOldReads(String loader, String path, String host) throws Exception {
        Path output = translate(loader, path, host, writeMod(loader, "1.20.1"));
        String keyAccessor = RetromodVersion.compareMcVersions(host, "1.21.11") >= 0 ? "identifier" : "location";
        try (JarFile jar = new JarFile(output.toFile())) {
            Path nested = Files.createTempFile(dir, "nested-", ".jar");
            Files.write(nested, jar.getInputStream(jar.getJarEntry(nestedPath(loader))).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                for (Map.Entry<String, JarFile> part : Map.of("mod", jar, "lib", lib).entrySet()) {
                    String where = part.getKey() + " (" + loader + " " + path + " on " + host + ")";
                    List<AbstractInsnNode> code = code(method(read(part.getValue(), part.getKey() + "/Loot"), "mapping"));
                    FieldInsnNode constant = fieldRead(code, where);
                    assertEquals(KEY, constant.desc, where + ": the host constant is a key");
                    MethodInsnNode toId = helperCall(code, "lootTableId", where);
                    MethodInsnNode all = helperCall(code, "allLootTableIds", where);
                    assertEquals(toId.owner, all.owner, where);
                    // The embedded helper travels with the call and reads the key with the host's accessor.
                    ClassNode helper = read(jar, lib, toId.owner);
                    assertTrue(code(method(helper, "lootTableId")).stream().anyMatch(insn -> insn instanceof MethodInsnNode c
                            && c.name.equals(keyAccessor)), where + ": the helper reads the key with " + keyAccessor);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0} {1} on {2}")
    @CsvSource({"forge, runtime, 26.3", "forge, transform, 26.2", "forge, aot, 26.3", "neoforge, runtime, 1.21.1"})
    @DisplayName("a 1.21.1 mod keeps reading keys and calling the host's all()")
    void newerModIsUntouched(String loader, String path, String host) throws Exception {
        Path output = translate(loader, path, host, writeMod(loader, "1.21.1"));
        try (JarFile jar = new JarFile(output.toFile())) {
            List<AbstractInsnNode> code = code(method(read(jar, "mod/Loot"), "mapping"));
            String where = loader + " " + path + " on " + host;
            assertEquals(KEY, fieldRead(code, where).desc, where);
            assertTrue(code.stream().anyMatch(insn -> insn instanceof MethodInsnNode c && c.owner.equals(BUILT_IN)
                    && c.name.equals("all")), where + ": all() stays the host's");
            assertTrue(code.stream().noneMatch(insn -> insn instanceof MethodInsnNode c && c.owner.endsWith(HELPER)),
                    where + ": a 1.21.1 mod takes no loot table id helper");
        }
    }

    /** Translates {@code input} for a {@code loader} host through one path and returns the output jar. */
    private Path translate(String loader, String path, String host, Path input) throws Exception {
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader.equals("fabric") ? null : loader);
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> runtime(loader, host, input, out);
            case "transform" -> {
                CliHarness.transform(input, out.resolve("lootmod.jar"), host);
                yield out.resolve("lootmod.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("lootmod.jar"), host);
                yield out.resolve("lootmod.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                try (var jars = Files.list(out)) {
                    yield jars.filter(jar -> jar.toString().endsWith(".jar")).findFirst().orElseThrow();
                }
            }
        };
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** Registers what the loader's entry point registers on {@code host}, then runs its mod transformer. */
    private Path runtime(String loader, String host, Path input, Path out) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        if (loader.equals("fabric")) {
            RetromodPreLaunch.registerShimsForTransform(host);
            return new FabricModTransformer(host).transformMod(input, out);
        }
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

    private Path writeMod(String loader, String builtFor) throws Exception {
        Map<String, byte[]> mod = new LinkedHashMap<>(fixture(loader, "mod", builtFor));
        mod.put(nestedPath(loader), LoaderApiFixtures.jar(fixture(loader, "lib", builtFor)));
        Path input = Files.createDirectories(dir.resolve("in")).resolve("lootmod-" + loader + ".jar");
        Files.write(input, LoaderApiFixtures.jar(mod));
        return input;
    }

    private static String nestedPath(String loader) {
        return loader.equals("fabric") ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    /** A Forge 1.20.1 mod runs on NeoForge too, so both loaders take the Forge fixture; Fabric takes intermediary names. */
    private static Map<String, byte[]> fixture(String loader, String pkg, String builtFor) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String id = "loot" + pkg;
        boolean old = builtFor.equals("1.20.1");
        if (loader.equals("fabric")) {
            entries.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\","
                    + "\"depends\":{\"minecraft\":\"" + builtFor + "\",\"fabricloader\":\">=0.14.0\"}"
                    + (pkg.equals("mod") ? ",\"jars\":[{\"file\":\"META-INF/jars/lib.jar\"}]" : "") + "}")
                    .getBytes(StandardCharsets.UTF_8));
        } else {
            entries.put("META-INF/mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                    + "[[mods]]\nmodId=\"" + id + "\"\nversion=\"1.0.0\"\n[[dependencies." + id + "]]\nmodId=\"minecraft\"\n"
                    + "mandatory=true\nversionRange=\"" + (old ? "[1.20.1,1.21)" : "[1.21.1,1.22)")
                    + "\"\nordering=\"NONE\"\nside=\"BOTH\"\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        entries.put(pkg + "/Loot.class", loot(pkg + "/Loot", loader.equals("fabric"), old));
        return entries;
    }

    /**
     * Sophisticated Backpacks' entity loot mapping and Lootr's table list: {@code static Object mapping(Map map)
     * { map.put("pyramid", BuiltInLootTables.DESERT_PYRAMID); return BuiltInLootTables.all(); }}, in the names
     * the build shipped: SRG for Forge 1.20.1, intermediary for Fabric 1.20.1, Mojang keys for 1.21.1.
     */
    private static byte[] loot(String name, boolean fabric, boolean old) {
        String owner = fabric ? "net/minecraft/class_39" : BUILT_IN;
        String field = fabric ? "field_885" : old ? "f_78764_" : "DESERT_PYRAMID";
        String all = fabric ? "method_270" : old ? "m_78766_" : "all";
        String type = !old ? KEY : fabric ? "Lnet/minecraft/class_2960;" : "Lnet/minecraft/resources/ResourceLocation;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "mapping", "(Ljava/util/Map;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("pyramid");
        mv.visitFieldInsn(GETSTATIC, owner, field, type);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitMethodInsn(INVOKESTATIC, owner, all, "()Ljava/util/Set;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static FieldInsnNode fieldRead(List<AbstractInsnNode> code, String where) {
        return code.stream().filter(insn -> insn instanceof FieldInsnNode f && f.owner.equals(BUILT_IN)
                        && f.name.equals("DESERT_PYRAMID"))
                .map(FieldInsnNode.class::cast).findFirst()
                .orElseThrow(() -> new AssertionError(where + ": no DESERT_PYRAMID read in " + describe(code)));
    }

    private static MethodInsnNode helperCall(List<AbstractInsnNode> code, String name, String where) {
        MethodInsnNode call = code.stream().filter(insn -> insn instanceof MethodInsnNode c && c.name.equals(name))
                .map(MethodInsnNode.class::cast).findFirst()
                .orElseThrow(() -> new AssertionError(where + ": no " + name + " call in " + describe(code)));
        assertTrue(call.owner.startsWith(SyntheticEmbedder.PREFIX) && call.owner.endsWith(HELPER),
                where + ": " + call.owner + " is not the embedded helper");
        return call;
    }

    private static String describe(List<AbstractInsnNode> code) {
        List<String> parts = new ArrayList<>();
        for (AbstractInsnNode insn : code) {
            if (insn instanceof MethodInsnNode c) parts.add(c.owner + "." + c.name + c.desc);
            if (insn instanceof FieldInsnNode f) parts.add(f.owner + "." + f.name + ":" + f.desc);
        }
        return parts.toString();
    }

    /** A class from the nested library, or from the outer jar where the embedder put it. */
    private static ClassNode read(JarFile jar, JarFile lib, String name) throws Exception {
        return lib.getJarEntry(name + ".class") != null ? read(lib, name) : read(jar, name);
    }

    private static ClassNode read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name));
    }

    private static List<AbstractInsnNode> code(MethodNode method) {
        List<AbstractInsnNode> code = new ArrayList<>();
        method.instructions.forEach(code::add);
        return code;
    }
}
