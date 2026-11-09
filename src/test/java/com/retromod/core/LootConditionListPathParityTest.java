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

/** Every path gives a 1.21.1 conditional loot function, in a mod and its nested library, its condition list on 26.3. */
class LootConditionListPathParityTest {

    private static final String FUNCTION = "net/minecraft/world/level/storage/loot/functions/LootItemConditionalFunction";
    private static final String FIELDS = "com/retromod/generated/LegacyConditionalFunctionFields";
    private static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyLootSerializers";
    private static final String INSTANCE = "Lcom/mojang/serialization/codecs/RecordCodecBuilder$Instance;";
    private static final String P1 = "Lcom/mojang/datafixers/Products$P1;";

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
            "neoforge, runtime, 26.2", "fabric, transform, 26.2"})
    @DisplayName("commonFields, simpleBuilder and super(list) reach the embedded helpers on 26.3 and stay on 26.2")
    void everyPathGivesTheList(String loader, String path, String host) throws Exception {
        boolean bridged = RetromodVersion.compareMcVersions(host, "26.3") >= 0;
        Path output = translate(loader, path, host, writeMod(loader));

        try (JarFile jar = new JarFile(output.toFile())) {
            Path nested = Files.createTempFile(dir, "nested-", ".jar");
            Files.write(nested, jar.getInputStream(jar.getJarEntry(nestedPath(loader))).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                for (Map.Entry<String, JarFile> part : Map.of("mod", jar, "lib", lib).entrySet()) {
                    String where = part.getKey() + " (" + loader + " " + path + " on " + host + ")";
                    ClassNode function = read(part.getValue(), part.getKey() + "/SkilletFunction");
                    MethodInsnNode fields = only(method(function, "fields"));
                    MethodInsnNode builder = only(method(function, "builder"));
                    List<MethodInsnNode> init = calls(method(function, "<init>"));
                    if (!bridged) {
                        assertEquals(function.name, fields.owner, where);
                        assertEquals("(Ljava/util/List;)V", init.get(init.size() - 1).desc, where);
                        continue;
                    }
                    for (MethodInsnNode call : List.of(fields, builder)) {
                        assertTrue(call.owner.startsWith(SyntheticEmbedder.PREFIX) && call.owner.endsWith(FIELDS),
                                where + ": " + call.owner + "." + call.name);
                    }
                    ClassNode helper = read(jar, lib, fields.owner);
                    assertEquals(FUNCTION, helper.superName, where + ": the helper may call the host's protected helpers");
                    assertTrue(init.get(0).owner.endsWith(SUPPORT) && init.get(0).name.equals("conditionHolder"), where);
                    assertEquals(FUNCTION + ".<init>(Ljava/util/Optional;)V",
                            init.get(1).owner + "." + init.get(1).name + init.get(1).desc, where);
                    assertNotNull(read(jar, lib, init.get(0).owner), where + ": the embedded support travels with the call");
                }
            }
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
                CliHarness.transform(input, out.resolve("skillet.jar"), host);
                yield out.resolve("skillet.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("skillet.jar"), host);
                yield out.resolve("skillet.jar");
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
        Path input = Files.createDirectories(dir.resolve("in")).resolve("skillet-" + loader + ".jar");
        Files.write(input, LoaderApiFixtures.jar(mod));
        return input;
    }

    private static String nestedPath(String loader) {
        return loader.equals("fabric") ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    /** A 1.21.1 mod for {@code loader} with Farmer's Delight's copy skillet function shape. */
    private static Map<String, byte[]> fixture(String loader, String pkg) {
        String id = "skillet" + pkg;
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
        entries.put(pkg + "/SkilletFunction.class", function(loader, pkg + "/SkilletFunction"));
        return entries;
    }

    /**
     * {@code class SkilletFunction extends LootItemConditionalFunction} as javac writes it: its constructor passes a list,
     * and its own class names the inherited commonFields and simpleBuilder. Intermediary names on Fabric.
     */
    private static byte[] function(String loader, String name) {
        boolean fabric = loader.equals("fabric");
        String base = fabric ? "net/minecraft/class_120" : FUNCTION;
        String builderDesc = "(Ljava/util/function/Function;)L" + base + (fabric ? "$class_121;" : "$Builder;");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT, name, null, base, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/List;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, base, "<init>", "(Ljava/util/List;)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        staticCall(cw, name, "fields", "(" + INSTANCE + ")" + P1, fabric ? "method_53344" : "commonFields");
        staticCall(cw, name, "builder", builderDesc, fabric ? "method_520" : "simpleBuilder");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void staticCall(ClassWriter cw, String owner, String name, String desc, String helper) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, owner, helper, desc, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
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

    private static MethodInsnNode only(MethodNode method) {
        List<MethodInsnNode> calls = calls(method);
        assertEquals(1, calls.size(), method.name + " makes one call");
        return calls.get(0);
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }
}
