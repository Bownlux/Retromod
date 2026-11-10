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

/** Every class-transform path sends an old mod's early item stacks to the helper copy in the same jar, outer and nested jar alike. */
class EarlyItemStackPathParityTest {

    private static final String HELPER_SUFFIX = "/com/retromod/legacyapi/minecraft/world/item/EarlyItemStacks";
    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    /** ItemStack, ItemLike, Item and getDefaultInstance as each loader's mods name them. */
    private static final Map<String, List<String>> NAMES = Map.of(
            "fabric", List.of("net/minecraft/class_1799", "net/minecraft/class_1935", "net/minecraft/class_1792", "method_7854"),
            "forge", List.of(ITEM_STACK, "net/minecraft/world/level/ItemLike", "net/minecraft/world/item/Item", "m_7968_"),
            "neoforge", List.of(ITEM_STACK, "net/minecraft/world/level/ItemLike", "net/minecraft/world/item/Item",
                    "getDefaultInstance"));

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
    @CsvSource({"fabric, runtime, 26.3", "fabric, transform, 26.3", "fabric, batch, 26.1", "fabric, aot, 26.3",
            "forge, runtime, 26.2", "forge, runtime, 26.3", "forge, transform, 26.2", "forge, batch, 26.3",
            "forge, aot, 26.2", "neoforge, runtime, 26.1", "neoforge, runtime, 26.3", "neoforge, transform, 26.3",
            "neoforge, batch, 26.3", "neoforge, aot, 26.3"})
    @DisplayName("each path calls the embedded helper, and the helper calls Minecraft's own constructors")
    void everyPathCallsTheEmbeddedHelper(String loader, String path, String host) throws Exception {
        Path output = translate(loader, path, host);

        String found = "[of, defaultInstance] on the embedded helper";
        assertEquals(Map.of("mod/Stacks", found, "nested:lib/LibStacks", found), summary(output, loader));
        assertHelperCallsTheHost(output);
    }

    @ParameterizedTest(name = "{0} {1} on {2}")
    @CsvSource({"fabric, runtime, 1.21.11", "neoforge, runtime, 1.21.4", "forge, transform, 1.21.11"})
    @DisplayName("a host that binds components with its items keeps the mod's own calls")
    void earlyBindingHostsKeepTheCalls(String loader, String path, String host) throws Exception {
        Path output = translate(loader, path, host);

        for (String described : summary(output, loader).values()) {
            assertFalse(described.contains("embedded helper"), described);
        }
    }

    private Path translate(String loader, String path, String host) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("stacks.jar");
        Files.write(input, modJar(loader));
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader.equals("fabric") ? null : loader);
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> runtime(loader, host, input, out);
            case "transform" -> {
                CliHarness.transform(input, out.resolve("stacks.jar"), host);
                yield out.resolve("stacks.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("stacks.jar"), host);
                yield out.resolve("stacks.jar");
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

    /** Where each class's stack calls go, for the mod and its nested library. */
    private static Map<String, String> summary(Path output, String loader) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            found.put("mod/Stacks", describe(jar, "mod/Stacks"));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                JarEntry entry = jar.getJarEntry(nestedPath(loader));
                assertNotNull(entry, "the nested library is missing from " + output.getFileName());
                Files.write(nested, jar.getInputStream(entry).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    found.put("nested:lib/LibStacks", describe(lib, "lib/LibStacks"));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    /** The names {@code build()} calls, and whether every call reaches a helper copy inside this jar. */
    private static String describe(JarFile jar, String className) throws Exception {
        MethodNode build = method(read(jar, className), "build");
        List<String> names = new ArrayList<>();
        List<String> elsewhere = new ArrayList<>();
        for (AbstractInsnNode insn : build.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            names.add(call.name);
            boolean embedded = call.owner.startsWith(SyntheticEmbedder.PREFIX) && call.owner.endsWith(HELPER_SUFFIX)
                    && jar.getJarEntry(call.owner + ".class") != null;
            if (!embedded) elsewhere.add(call.owner + "." + call.name);
        }
        return names + (elsewhere.isEmpty() ? " on the embedded helper" : " with calls to " + elsewhere);
    }

    /** The embedded copy still builds Minecraft's stack once bound, and every Retromod class it names is in the jar. */
    private static void assertHelperCallsTheHost(Path output) throws Exception {
        try (JarFile jar = new JarFile(output.toFile())) {
            String helper = jar.stream().map(JarEntry::getName)
                    .filter(name -> name.startsWith(SyntheticEmbedder.PREFIX) && name.endsWith(HELPER_SUFFIX + ".class"))
                    .findFirst().orElseThrow(() -> new AssertionError("no embedded helper in " + output.getFileName()));
            ClassNode copy = read(jar, helper.substring(0, helper.length() - ".class".length()));
            List<String> calls = new ArrayList<>();
            for (MethodNode method : copy.methods) {
                if (!method.name.equals("of")) continue;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.name.equals("<init>")) calls.add(call.owner);
                    if (insn instanceof MethodInsnNode call && call.name.equals("of")) fail("the copy calls itself: " + call.owner);
                }
            }
            assertEquals(List.of(ITEM_STACK, ITEM_STACK, ITEM_STACK, ITEM_STACK, ITEM_STACK), calls);
            for (String referenced : referencedRetromodClasses(jar, helper)) {
                assertNotNull(jar.getJarEntry(referenced + ".class"), "the helper names " + referenced + ", missing from the jar");
            }
        }
    }

    /** Every Retromod class an embedded class names, followed through the classes it names in turn. */
    private static Set<String> referencedRetromodClasses(JarFile jar, String start) throws Exception {
        Set<String> seen = new java.util.TreeSet<>();
        java.util.ArrayDeque<String> work = new java.util.ArrayDeque<>(List.of(start.substring(0, start.length() - 6)));
        while (!work.isEmpty()) {
            String name = work.poll();
            if (!seen.add(name)) continue;
            JarEntry entry = jar.getJarEntry(name + ".class");
            if (entry == null) continue;
            String text = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.ISO_8859_1);
            java.util.regex.Matcher names = java.util.regex.Pattern
                    .compile(java.util.regex.Pattern.quote(SyntheticEmbedder.PREFIX) + "[A-Za-z0-9_/$]+").matcher(text);
            while (names.find()) work.add(names.group());
        }
        return seen;
    }

    private static ClassNode read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry, className + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static String nestedPath(String loader) {
        return loader.equals("fabric") ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    /** A mod for {@code loader}'s 1.20.1 or 1.21.1 that builds stacks, and a nested library mod that does the same. */
    private static byte[] modJar(String loader) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put(metadataPath(loader), metadata(loader, "stackslib", false));
        lib.put("lib/LibStacks.class", stacksClass("lib/LibStacks", loader));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(metadataPath(loader), metadata(loader, "stacks", true));
        mod.put("mod/Stacks.class", stacksClass("mod/Stacks", loader));
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

    /** {@code static void build(ItemLike, Item)} making a stack of two and asking for the default stack, by {@code loader}'s names. */
    private static byte[] stacksClass(String name, String loader) {
        List<String> names = NAMES.get(loader);
        String stack = names.get(0);
        String itemLike = names.get(1);
        String item = names.get(2);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build",
                "(L" + itemLike + ";L" + item + ";)V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, stack);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, stack, "<init>", "(L" + itemLike + ";I)V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, item, names.get(3), "()L" + stack + ";", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
