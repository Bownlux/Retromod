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
import org.objectweb.asm.Opcodes;
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
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Every class-transform path sends an old mod's foliage color getters to the helper embedded in the same jar, outer and nested jar alike. */
class Legacy1214MemberPathParityTest {

    private static final String FOLIAGE = "net/minecraft/world/level/FoliageColor";
    private static final List<String> GETTERS =
            List.of("getEvergreenColor", "getBirchColor", "getDefaultColor", "getMangroveColor");
    /** The same four getters as each loader's mods name them. */
    private static final Map<String, List<String>> NAMES = Map.of(
            "fabric", List.of("method_8342", "method_8343", "method_8341", "method_43717"),
            "forge", List.of("m_46106_", "m_46112_", "m_46113_", "m_220346_"),
            "neoforge", GETTERS);

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
    @CsvSource({"fabric, runtime, 26.3", "fabric, runtime, 1.21.11", "fabric, transform, 26.3",
            "fabric, transform, 1.21.11", "fabric, batch, 26.3", "fabric, aot, 26.3",
            "forge, runtime, 26.2", "forge, runtime, 26.3", "forge, transform, 26.2", "forge, batch, 26.3",
            "forge, aot, 26.2", "neoforge, runtime, 1.21.4", "neoforge, runtime, 26.3",
            "neoforge, transform, 26.3", "neoforge, batch, 26.3", "neoforge, aot, 26.3"})
    @DisplayName("each path calls the embedded helper from the mod and from its nested library")
    void everyPathCallsTheEmbeddedHelper(String loader, String path, String host) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("leaves.jar");
        Files.write(input, modJar(loader));

        Path output = translate(loader, path, host, input);

        String found = GETTERS + " on the embedded helper";
        assertEquals(Map.of("mod/LeafColors", found, "nested:lib/LibLeafColors", found), summary(output, loader));
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
                CliHarness.transform(input, out.resolve("leaves.jar"), host);
                yield out.resolve("leaves.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("leaves.jar"), host);
                yield out.resolve("leaves.jar");
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
        assertNotNull(output, path + " must produce a jar");
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

    /** Where each class's getter calls go, for the mod and its nested library. */
    private static Map<String, String> summary(Path output, String loader) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            found.put("mod/LeafColors", describe(jar, "mod/LeafColors"));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                JarEntry entry = jar.getJarEntry(nestedPath(loader));
                assertNotNull(entry, "the nested library is missing from " + output.getFileName());
                Files.write(nested, jar.getInputStream(entry).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    found.put("nested:lib/LibLeafColors", describe(lib, "lib/LibLeafColors"));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    /** The names {@code colors()} calls, and whether every call reaches a helper copy inside this jar. */
    private static String describe(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry, className + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        MethodNode colors = node.methods.stream().filter(m -> m.name.equals("colors")).findFirst().orElseThrow();
        List<String> names = new ArrayList<>();
        List<String> elsewhere = new ArrayList<>();
        for (AbstractInsnNode insn : colors.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            names.add(call.name);
            boolean embedded = call.owner.startsWith(SyntheticEmbedder.PREFIX)
                    && call.owner.endsWith("/Legacy1214Members") && jar.getJarEntry(call.owner + ".class") != null;
            if (!embedded) elsewhere.add(call.owner + "." + call.name);
        }
        return names + (elsewhere.isEmpty() ? " on the embedded helper" : " with calls to " + elsewhere);
    }

    private static String nestedPath(String loader) {
        return loader.equals("fabric") ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    /** A mod for {@code loader}'s 1.20.1 or 1.21.1 that reads the four colors, and a nested library mod that does the same. */
    private static byte[] modJar(String loader) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put(metadataPath(loader), metadata(loader, "leaveslib", false));
        lib.put("lib/LibLeafColors.class", colorsClass("lib/LibLeafColors", loader));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(metadataPath(loader), metadata(loader, "leaves", true));
        mod.put("mod/LeafColors.class", colorsClass("mod/LeafColors", loader));
        mod.put(nestedPath(loader), LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    private static String metadataPath(String loader) {
        return switch (loader) {
            case "fabric" -> "fabric.mod.json";
            case "forge" -> "META-INF/mods.toml";
            default -> "META-INF/neoforge.mods.toml";
        };
    }

    private static byte[] metadata(String loader, String modId, boolean nestsLibrary) {
        String text = switch (loader) {
            case "fabric" -> "{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0.0\","
                    + "\"depends\":{\"minecraft\":\"1.21.1\",\"fabricloader\":\">=0.15.0\"}"
                    + (nestsLibrary ? ",\"jars\":[{\"file\":\"META-INF/jars/lib.jar\"}]" : "") + "}";
            case "forge" -> "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""
                    + modId + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\n"
                    + "mandatory=true\nversionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
            default -> "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""
                    + modId + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\n"
                    + "type=\"required\"\nversionRange=\"[1.21.1,1.22)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
        };
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code static void colors()} calling the four getters by {@code loader}'s names, as a color handler does. */
    private static byte[] colorsClass(String name, String loader) {
        String owner = loader.equals("fabric") ? "net/minecraft/class_1926" : FOLIAGE;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "colors", "()V", null, null);
        mv.visitCode();
        for (String getter : NAMES.get(loader)) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, getter, "()I", false);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
