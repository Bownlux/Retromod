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

/** Every class-transform path gives an old command context the members a 26.x host asks for, outer and nested jar alike. */
class RegistryProviderPathParityTest {

    private static final String HELPER = "/LegacyHolderLookups";
    /** A 1.20.1 context: its holderLookup answers the provider lookup, empty for a missing registry, and its calls go to the embedded helper. */
    private static final String OLD_CONTEXT = "enabledFeatures=[helper.enabledFeatures], holderLookup=[helper.holderLookup, "
            + "helper.filterElements], listRegistryKeys=[helper.registryKeys], "
            + "lookup=[holderLookup, helper.lookup, Optional.empty]";
    /** A 1.21.1 context: its key list takes the new name, and its features come from the embedded helper. */
    private static final String KEYED_CONTEXT = "enabledFeatures=[helper.enabledFeatures], "
            + "listRegistryKeys=[CommandBuildContext.listRegistryKeys], lookup=[CommandBuildContext.lookup]";

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
    @CsvSource({"fabric, runtime, 26.3", "fabric, transform, 26.3", "fabric, batch, 26.2", "fabric, aot, 26.3",
            "forge, runtime, 26.2", "forge, runtime, 26.3", "forge, transform, 26.2", "forge, batch, 26.3",
            "forge, aot, 26.2", "neoforge, runtime, 26.3", "neoforge, transform, 26.2", "neoforge, batch, 26.3",
            "neoforge, aot, 26.3"})
    @DisplayName("each path bridges the mod's context and its nested library's the same way")
    void everyPathBridgesTheContext(String loader, String path, String host) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("backpacks.jar");
        Files.write(input, modJar(loader));

        Path output = translate(loader, path, host, input);

        String expected = loader.equals("neoforge") ? KEYED_CONTEXT : OLD_CONTEXT;
        assertEquals(Map.of("mod/BackpackContext", expected, "nested:lib/LibContext", expected), summary(output, loader));
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
                CliHarness.transform(input, out.resolve("backpacks.jar"), host);
                yield out.resolve("backpacks.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("backpacks.jar"), host);
                yield out.resolve("backpacks.jar");
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

    /** What each context class declares and calls, for the mod and its nested library. */
    private static Map<String, String> summary(Path output, String loader) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            found.put("mod/BackpackContext", describe(jar, "mod/BackpackContext"));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                JarEntry entry = jar.getJarEntry(nestedPath(loader));
                assertNotNull(entry, "the nested library is missing from " + output.getFileName());
                Files.write(nested, jar.getInputStream(entry).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    found.put("nested:lib/LibContext", describe(lib, "lib/LibContext"));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    /** Each instance method and the calls it makes, with a call to a helper copy inside this jar shown as {@code helper}. */
    private static String describe(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry, className + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        Map<String, List<String>> methods = new TreeMap<>();
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("<")) continue;
            List<String> calls = new ArrayList<>();
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(callName(jar, className, call));
            }
            methods.put(method.name, calls);
        }
        return methods.toString().replaceAll("^\\{|\\}$", "");
    }

    private static String callName(JarFile jar, String className, MethodInsnNode call) {
        // The helper and both of its lookup views must have been embedded into the same jar.
        boolean embedded = call.owner.startsWith(SyntheticEmbedder.PREFIX) && call.owner.endsWith(HELPER)
                && jar.getJarEntry(call.owner + ".class") != null && jar.getJarEntry(call.owner + "$Filtered.class") != null
                && jar.getJarEntry(call.owner + "$Keyed.class") != null;
        if (embedded) return "helper." + call.name;
        if (call.owner.equals(className)) return call.name;
        return call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name;
    }

    private static String nestedPath(String loader) {
        return loader.equals("fabric") ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    /** A mod with a context class in {@code loader}'s names, and a nested library mod with the same class. */
    private static byte[] modJar(String loader) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put(metadataPath(loader), metadata(loader, "backpackslib", false));
        lib.put("lib/LibContext.class", contextClass("lib/LibContext", loader));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(metadataPath(loader), metadata(loader, "backpacks", true));
        mod.put("mod/BackpackContext.class", contextClass("mod/BackpackContext", loader));
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

    // Fabric and Forge mods here are built for 1.20.1, the NeoForge one for 1.21.1.
    private static byte[] metadata(String loader, String modId, boolean nestsLibrary) {
        String text = switch (loader) {
            case "fabric" -> "{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0.0\","
                    + "\"depends\":{\"minecraft\":\"1.20.1\",\"fabricloader\":\">=0.14.0\"}"
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

    /** Sophisticated Backpacks' item argument context in {@code loader}'s names, wrapping the context it was given. */
    private static byte[] contextClass(String name, String loader) {
        boolean fabric = loader.equals("fabric");
        String context = fabric ? "net/minecraft/class_7157" : "net/minecraft/commands/CommandBuildContext";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", new String[] {context});
        cw.visitField(Opcodes.ACC_FINAL, "delegate", "L" + context + ";", null, null).visitEnd();
        if (loader.equals("neoforge")) {
            // 1.21.1: the context is a provider that lists its keys through listRegistries.
            delegating(cw, name, context, "listRegistries", "()Ljava/util/stream/Stream;", null, null);
            delegating(cw, name, context, "lookup", "(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;", null, null);
        } else {
            // 1.20.1: the context answers holderLookup(key), filtered by the old HolderLookup.filterElements.
            String key = fabric ? "net/minecraft/class_5321" : "net/minecraft/resources/ResourceKey";
            String lookup = fabric ? "net/minecraft/class_7225" : "net/minecraft/core/HolderLookup";
            String holderLookup = fabric ? "method_41699" : "m_227133_";
            String filter = fabric ? "method_46753" : "m_255348_";
            delegating(cw, name, context, holderLookup, "(L" + key + ";)L" + lookup + ";", lookup, filter);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code method} calling the same method on {@code delegate}, then {@code filter(null)} on its result when given. */
    private static void delegating(ClassWriter cw, String owner, String context, String method, String desc,
            String lookup, String filter) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "delegate", "L" + context + ";");
        for (int i = 0; i < org.objectweb.asm.Type.getArgumentTypes(desc).length; i++) mv.visitVarInsn(Opcodes.ALOAD, i + 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, context, method, desc, true);
        if (filter != null) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, lookup, filter, "(Ljava/util/function/Predicate;)L" + lookup + ";", true);
        }
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
