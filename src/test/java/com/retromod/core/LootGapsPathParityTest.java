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

/** Every path sends a 1.21.1 mod's old loot reads, in the mod and its nested library, to the embedded bridges. */
class LootGapsPathParityTest {

    private static final String ENTRIES = "net/minecraft/world/level/storage/loot/entries/";
    private static final String TYPES = "com/retromod/generated/LegacyLootEntryTypes";
    private static final String NAMED_TABLE = "com/retromod/legacyapi/minecraft/world/level/storage/loot/entries/LegacyNamedLootTable";
    private static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyLootSerializers";

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
    @CsvSource({"forge, runtime, 26.3", "forge, transform, 26.3", "forge, batch, 26.3", "forge, aot, 26.3",
            "neoforge, runtime, 26.3", "neoforge, transform, 26.3", "neoforge, batch, 26.3", "neoforge, aot, 26.3",
            "fabric, runtime, 26.3", "fabric, transform, 26.3", "fabric, aot, 26.3",
            "neoforge, runtime, 26.2", "fabric, transform, 26.2", "neoforge, transform, 1.21.11"})
    @DisplayName("entry types from 26.1, and a nested table by name and the old entry lists from 26.3, reach the embedded bridges")
    void everyPathReachesTheBridges(String loader, String path, String host) throws Exception {
        boolean typesGone = RetromodVersion.compareMcVersions(host, "26.1") >= 0;
        boolean entriesRebuilt = RetromodVersion.compareMcVersions(host, "26.3") >= 0;
        Path output = translate(loader, path, host, writeMod(loader));

        try (JarFile jar = new JarFile(output.toFile())) {
            Path nested = Files.createTempFile(dir, "nested-", ".jar");
            Files.write(nested, jar.getInputStream(jar.getJarEntry(nestedPath(loader))).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                for (Map.Entry<String, JarFile> part : Map.of("mod", jar, "lib", lib).entrySet()) {
                    String where = part.getKey() + " (" + loader + " " + path + " on " + host + ")";
                    ClassNode reads = read(part.getValue(), part.getKey() + "/LootGaps");
                    assertEmbedded(jar, lib, typesGone, TYPES, "ITEM", calls(method(reads, "itemType")), where);
                    assertEmbedded(jar, lib, typesGone, TYPES, "typeOf", calls(method(reads, "typeOf")), where);
                    assertEmbedded(jar, lib, entriesRebuilt, NAMED_TABLE, "reference", calls(method(reads, "nested")), where);
                    assertEmbedded(jar, lib, entriesRebuilt, SUPPORT, "entryFunctions", calls(method(reads, "functionsOf")), where);
                }
            }
        }
    }

    /** The method's one call reaches the embedded copy of {@code owner} when {@code bridged}, and nothing embedded otherwise. */
    private static void assertEmbedded(JarFile jar, JarFile lib, boolean bridged, String owner, String name,
            List<MethodInsnNode> calls, String where) throws Exception {
        MethodInsnNode embedded = calls.stream().filter(call -> call.owner.startsWith(SyntheticEmbedder.PREFIX)).findFirst().orElse(null);
        if (!bridged) {
            assertNull(embedded, where + ": " + owner + " is for later hosts");
            return;
        }
        assertNotNull(embedded, where + ": no call reaches " + owner + " in " + describe(calls));
        assertTrue(embedded.owner.endsWith(owner) && embedded.name.equals(name), where + ": " + embedded.owner + "." + embedded.name);
        assertNotNull(read(jar, lib, embedded.owner), where + ": the bridge travels with the mod");
    }

    private static String describe(List<MethodInsnNode> calls) {
        return calls.stream().map(call -> call.owner + "." + call.name + call.desc).toList().toString();
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
                CliHarness.transform(input, out.resolve("gaps.jar"), host);
                yield out.resolve("gaps.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("gaps.jar"), host);
                yield out.resolve("gaps.jar");
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

    private Path writeMod(String loader) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>(fixture(loader, "lib"));
        Map<String, byte[]> mod = new LinkedHashMap<>(fixture(loader, "mod"));
        mod.put(nestedPath(loader), LoaderApiFixtures.jar(lib));
        Path input = Files.createDirectories(dir.resolve("in")).resolve("gaps-" + loader + ".jar");
        Files.write(input, LoaderApiFixtures.jar(mod));
        return input;
    }

    private static String nestedPath(String loader) {
        return loader.equals("fabric") ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    /** A 1.21.1 mod for {@code loader} that reads loot as Bookshelf, Moonlight and loot viewers do. */
    private static Map<String, byte[]> fixture(String loader, String pkg) {
        String id = "gaps" + pkg;
        String metadata = switch (loader) {
            case "fabric" -> "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\","
                    + "\"depends\":{\"minecraft\":\"1.21.1\",\"fabricloader\":\">=0.15.0\"}"
                    + (pkg.equals("mod") ? ",\"jars\":[{\"file\":\"META-INF/jars/lib.jar\"}]" : "") + "}";
            default -> "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id
                    + "\"\nversion=\"1.0.0\"\n[[dependencies." + id + "]]\nmodId=\"minecraft\"\ntype=\"required\"\n"
                    + "mandatory=true\nversionRange=\"[1.21.1,1.22)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
        };
        String metadataPath = switch (loader) {
            case "fabric" -> "fabric.mod.json";
            case "forge" -> "META-INF/mods.toml";
            default -> "META-INF/neoforge.mods.toml";
        };
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(metadataPath, metadata.getBytes(StandardCharsets.UTF_8));
        entries.put(pkg + "/LootGaps.class", reads(loader, pkg + "/LootGaps"));
        return entries;
    }

    /**
     * The four reads as javac writes them: a removed type constant, getType() on an entry, a nested table by key in a
     * method with no registries, and an entry's function list read from outside. Intermediary names on Fabric.
     */
    private static byte[] reads(String loader, String name) {
        boolean fabric = loader.equals("fabric");
        String type = fabric ? "Lnet/minecraft/class_5338;" : "L" + ENTRIES + "LootPoolEntryType;";
        String container = fabric ? "net/minecraft/class_79" : ENTRIES + "LootPoolEntryContainer";
        String singleton = fabric ? "net/minecraft/class_85" : ENTRIES + "LootPoolSingletonContainer";
        String key = fabric ? "Lnet/minecraft/class_5321;" : "Lnet/minecraft/resources/ResourceKey;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);

        MethodVisitor item = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "itemType", "()" + type, null, null);
        item.visitCode();
        item.visitFieldInsn(GETSTATIC, fabric ? "net/minecraft/class_75" : ENTRIES + "LootPoolEntries", fabric ? "field_25207" : "ITEM", type);
        item.visitInsn(ARETURN);
        item.visitMaxs(0, 0);
        item.visitEnd();

        MethodVisitor typeOf = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "typeOf", "(L" + container + ";)" + type, null, null);
        typeOf.visitCode();
        typeOf.visitVarInsn(ALOAD, 0);
        typeOf.visitMethodInsn(INVOKEVIRTUAL, container, fabric ? "method_29318" : "getType", "()" + type, false);
        typeOf.visitInsn(ARETURN);
        typeOf.visitMaxs(0, 0);
        typeOf.visitEnd();

        String builder = "L" + singleton + (fabric ? "$class_86;" : "$Builder;");
        MethodVisitor nested = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "nested", "(" + key + ")" + builder, null, null);
        nested.visitCode();
        nested.visitVarInsn(ALOAD, 0);
        nested.visitMethodInsn(INVOKESTATIC, fabric ? "net/minecraft/class_83" : ENTRIES + "NestedLootTable",
                fabric ? "method_428" : "lootTableReference", "(" + key + ")" + builder, false);
        nested.visitInsn(ARETURN);
        nested.visitMaxs(0, 0);
        nested.visitEnd();

        MethodVisitor functions = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "functionsOf", "(L" + singleton + ";)Ljava/util/List;", null, null);
        functions.visitCode();
        functions.visitVarInsn(ALOAD, 0);
        functions.visitFieldInsn(GETFIELD, singleton, fabric ? "field_996" : "functions", "Ljava/util/List;");
        functions.visitInsn(ARETURN);
        functions.visitMaxs(0, 0);
        functions.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
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

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }
}
