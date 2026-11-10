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
import org.objectweb.asm.tree.FieldNode;
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

/** Every class-transform path repairs the getOrThrow, Tiers and final-override shapes that kept FD, JEI and Balm from verifying. */
class KnownGoodVerifyPathParityTest {

    private static final String HOST_BEHAVIOR = "com/retromod/shim/common/finalhost/HostBehavior";
    private static final String DATA_RESULT = "com/mojang/serialization/DataResult";

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
    @CsvSource({"forge, runtime, 1.20.6", "forge, runtime, 26.2", "forge, runtime, 26.3", "forge, transform, 26.2",
            "forge, batch, 26.3",
            "forge, aot, 26.2", "neoforge, runtime, 26.2", "neoforge, transform, 26.3", "neoforge, batch, 26.2",
            "neoforge, aot, 26.3", "fabric, runtime, 26.2", "fabric, runtime, 26.3", "fabric, transform, 26.2",
            "fabric, batch, 26.3", "fabric, aot, 26.2", "fabric, runtime, 1.21.11", "fabric, runtime, 1.20.4"})
    @DisplayName("each path repairs the old getOrThrow, the Tiers type and the final override, in the mod and its nested library")
    void everyPathRepairs(String loader, String path, String host) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("kitchen.jar");
        Files.write(input, modJar(loader));

        Path output = translate(loader, path, host, input);

        // 1.20.5 dropped getOrThrow(boolean, Consumer); the equipment records arrived on 26.x hosts.
        boolean dfuRebuilt = RetromodVersion.compareMcVersions(host, "1.20.5") >= 0;
        boolean tierRecord = RetromodVersion.isUnobfuscatedTarget(host);
        Map<String, String> expected = new TreeMap<>();
        for (String prefix : List.of("", "nested:")) {
            expected.put(prefix + "getOrThrow", dfuRebuilt ? "DataResultPolyfill.getOrThrow" : "DataResult.getOrThrow");
            expected.put(prefix + "tier", tierRecord ? "field, constant and call on the embedded old tier" : "left alone");
            expected.put(prefix + "values", tierRecord ? "values() and toString() on the embedded old tier" : "left alone");
            expected.put(prefix + "final", "renamed retromod$final$label, own call follows");
            expected.put(prefix + "opened", "title kept, as the jar's own access file opens it");
        }
        assertEquals(expected, summary(output, loader));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a class transformed with no jar in scope keeps its override, since the jar's access files are unknown")
    void noJarScopeLeavesOverridesAlone() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        transformer.clearRedirectsForTesting();
        transformer.registerMethodRedirect("mod/Unrelated", "old", "()V", "mod/Unrelated", "fresh", "()V");
        byte[] behavior = behavior("mod/Behavior");

        assertTrue(declares(transformer.transformClass(behavior, "mod/Behavior"), "label"),
                "a JIT, embed or verify transform has no jar scope and must not rename");
        try (var classes = transformer.pushJarClassBytesProvider(name -> name.equals("mod/Behavior") ? behavior : null);
             var opened = transformer.pushJarOpenedFinals(Set.of())) {
            assertTrue(declares(transformer.transformClass(behavior, "mod/Behavior"), "retromod$final$label"),
                    "inside its jar's scope the same class is repaired");
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the full AOT and hybrid background precompiles rename the final override and keep the opened one")
    void precompileEnginesRepairToo() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
        transformer.registerMethodRedirect("mod/Unrelated", "old", "()V", "mod/Unrelated", "fresh", "()V");
        Path root = dir.toRealPath();
        Path input = Files.write(root.resolve("kitchen.jar"), modJar("forge"));

        Map<String, byte[]> fullAot = new TreeMap<>();
        resetFullAot();
        try {
            com.retromod.aot.FullAotCompiler.getInstance(root, "26.2").runFullCompilation(List.of(input)).get();
        } finally {
            resetFullAot();
        }
        java.lang.reflect.Method cacheName = com.retromod.aot.FullAotCompiler.class
                .getDeclaredMethod("safeClassCacheFileName", String.class);
        cacheName.setAccessible(true);
        // Full AOT caches only the classes it changed, so an uncached one is the class as shipped.
        Map<String, byte[]> shipped = Map.of("mod/Behavior", behavior("mod/Behavior"), "mod/Opened", opened("mod/Opened"));
        for (String name : shipped.keySet()) {
            String file = (String) cacheName.invoke(null, name);
            try (var files = Files.walk(root.resolve("retromod-cache/full-aot"))) {
                Path cached = files.filter(p -> p.getFileName().toString().equals(file)).findFirst().orElse(null);
                fullAot.put(name, cached != null ? Files.readAllBytes(cached) : shipped.get(name));
            }
        }
        assertPrecompiled("full AOT", fullAot);

        // The hybrid engine's background pass, which pushes the jar's scope around its class loop.
        HybridTransformationEngine engine = HybridTransformationEngine.getInstance();
        Class<?> budgetType = Class.forName("com.retromod.core.HybridTransformationEngine$SharedByteBudget");
        var budgetConstructor = budgetType.getDeclaredConstructor(long.class);
        budgetConstructor.setAccessible(true);
        java.lang.reflect.Method compile = HybridTransformationEngine.class.getDeclaredMethod("compileModAot",
                HybridTransformationEngine.ModTransformInfo.class, String.class, budgetType);
        compile.setAccessible(true);
        compile.invoke(engine, new HybridTransformationEngine.ModTransformInfo(input, "kitchen", "kitchen", "1.20.1",
                Set.of("mod")), "26.2", budgetConstructor.newInstance(Long.MAX_VALUE));
        java.lang.reflect.Method cached = HybridTransformationEngine.class.getDeclaredMethod("cachedTransform",
                String.class, byte[].class);
        cached.setAccessible(true);
        Map<String, byte[]> hybrid = new TreeMap<>();
        hybrid.put("mod/Behavior", (byte[]) cached.invoke(engine, "mod/Behavior", behavior("mod/Behavior")));
        hybrid.put("mod/Opened", (byte[]) cached.invoke(engine, "mod/Opened", opened("mod/Opened")));
        assertPrecompiled("hybrid AOT", hybrid);
    }

    private static void assertPrecompiled(String path, Map<String, byte[]> classes) {
        assertNotNull(classes.get("mod/Behavior"), path + " must cache the behavior");
        assertNotNull(classes.get("mod/Opened"), path + " must cache the opened override");
        assertTrue(declares(classes.get("mod/Behavior"), "retromod$final$label"), path + " renames the final override");
        assertTrue(declares(classes.get("mod/Opened"), "title"), path + " keeps the override the jar's AT opens");
    }

    private static void resetFullAot() throws Exception {
        java.lang.reflect.Field instance = com.retromod.aot.FullAotCompiler.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static boolean declares(byte[] classBytes, String method) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node.methods.stream().anyMatch(m -> m.name.equals(method));
    }

    /** Translates {@code input} for a {@code loader} host through one path and returns the output jar. */
    private Path translate(String loader, String path, String host, Path input) throws Exception {
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader.equals("fabric") ? null : "forge");
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> runtime(loader, host, input, out);
            case "transform" -> {
                CliHarness.transform(input, out.resolve("kitchen.jar"), host);
                yield out.resolve("kitchen.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("kitchen.jar"), host);
                yield out.resolve("kitchen.jar");
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

    private static Map<String, String> summary(Path output, String loader) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            describe(jar, "mod/", "", found);
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                JarEntry entry = jar.getJarEntry(nestedPath(loader));
                assertNotNull(entry, "the nested library is missing from " + output.getFileName());
                Files.write(nested, jar.getInputStream(entry).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describe(lib, "lib/", "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static void describe(JarFile jar, String pkg, String prefix, Map<String, String> found) throws Exception {
        ClassNode codecs = read(jar, pkg + "Codecs");
        MethodInsnNode call = onlyCall(method(codecs, "decode"));
        found.put(prefix + "getOrThrow", call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);

        ClassNode skillet = read(jar, pkg + "Skillet");
        FieldNode field = skillet.fields.get(0);
        List<MethodInsnNode> constants = calls(method(skillet, "<clinit>"));
        MethodInsnNode uses = onlyCall(method(skillet, "uses"));
        String tier = "/com/retromod/generated/LegacyEquipmentTier";
        boolean fieldEmbedded = field.desc.startsWith("L" + SyntheticEmbedder.PREFIX) && field.desc.endsWith(tier + ";");
        boolean constantEmbedded = constants.size() == 1 && embeddedIn(jar, constants.get(0).owner, tier + "View");
        boolean callEmbedded = uses.getOpcode() == Opcodes.INVOKEINTERFACE && uses.name.equals("getUses")
                && embeddedIn(jar, uses.owner, tier);
        boolean untouched = constants.isEmpty() && (field.desc.contains("/Tiers;") || field.desc.contains("class_1834"));
        found.put(prefix + "tier", fieldEmbedded && constantEmbedded && callEmbedded
                ? "field, constant and call on the embedded old tier"
                : untouched ? "left alone" : "partly: " + field.desc + " " + constants.stream().map(c -> c.owner).toList()
                        + " " + uses.owner + "." + uses.name);

        // KubeJS keys tiers by Tiers.values()[i].toString(), so both reach the embedded interface and its views.
        List<MethodInsnNode> valueCalls = calls(method(skillet, "firstTierName"));
        boolean valuesEmbedded = valueCalls.size() == 2 && valueCalls.get(0).name.equals("values")
                && valueCalls.get(0).itf && embeddedIn(jar, valueCalls.get(0).owner, tier)
                && declares(jar, valueCalls.get(0).owner, "values") && valueCalls.get(1).name.equals("toString")
                && valueCalls.get(1).getOpcode() == Opcodes.INVOKEINTERFACE
                && declares(jar, valueCalls.get(0).owner + "View", "toString");
        boolean valuesUntouched = !valueCalls.isEmpty()
                && (valueCalls.get(0).owner.endsWith("/Tiers") || valueCalls.get(0).owner.endsWith("class_1834"));
        found.put(prefix + "values", valuesEmbedded ? "values() and toString() on the embedded old tier"
                : valuesUntouched ? "left alone" : "partly: " + valueCalls.stream().map(c -> c.owner + "." + c.name).toList());

        ClassNode behavior = read(jar, pkg + "Behavior");
        boolean renamed = behavior.methods.stream().anyMatch(m -> m.name.equals("retromod$final$label"))
                && behavior.methods.stream().noneMatch(m -> m.name.equals("label"));
        MethodInsnNode own = onlyCall(method(behavior, "own"));
        found.put(prefix + "final", (renamed ? "renamed retromod$final$label" : "label kept")
                + (own.name.equals("retromod$final$label") ? ", own call follows" : ", own call to " + own.name));

        ClassNode opened = read(jar, pkg + "Opened");
        found.put(prefix + "opened", opened.methods.stream().anyMatch(m -> m.name.equals("title"))
                ? "title kept, as the jar's own access file opens it" : "title renamed");
    }

    /** Whether the jar's class {@code owner} declares a method named {@code method}. */
    private static boolean declares(JarFile jar, String owner, String method) throws Exception {
        if (jar.getJarEntry(owner + ".class") == null) return false;
        return read(jar, owner).methods.stream().anyMatch(m -> m.name.equals(method));
    }

    /** Whether {@code owner} is this jar's own embedded copy of a generated class ending in {@code suffix}. */
    private static boolean embeddedIn(JarFile jar, String owner, String suffix) {
        return owner.startsWith(SyntheticEmbedder.PREFIX) && owner.endsWith(suffix)
                && jar.getJarEntry(owner + ".class") != null;
    }

    private static String nestedPath(String loader) {
        return loader.equals("fabric") ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    /** A 1.20.1 kitchen mod for {@code loader} (a Forge jar on NeoForge) and a nested library with the same classes. */
    private static byte[] modJar(String loader) throws Exception {
        Map<String, byte[]> lib = classes("lib/", loader);
        lib.put(metadataPath(loader), metadata(loader, "kitchenlib", false));
        lib.put(accessFilePath(loader), accessFile(loader));
        Map<String, byte[]> mod = classes("mod/", loader);
        mod.put(metadataPath(loader), metadata(loader, "kitchen", true));
        mod.put(accessFilePath(loader), accessFile(loader));
        mod.put(nestedPath(loader), LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    private static Map<String, byte[]> classes(String pkg, String loader) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(pkg + "Codecs.class", codecs(pkg + "Codecs"));
        classes.put(pkg + "Skillet.class", skillet(pkg + "Skillet", loader));
        classes.put(pkg + "Behavior.class", behavior(pkg + "Behavior"));
        classes.put(pkg + "Opened.class", opened(pkg + "Opened"));
        return classes;
    }

    private static String accessFilePath(String loader) {
        return loader.equals("fabric") ? "kitchen.accesswidener" : "META-INF/accesstransformer.cfg";
    }

    /** Opens HostBehavior.title(), as Farmer's Delight opens DefaultDispenseItemBehavior.dispense. */
    private static byte[] accessFile(String loader) {
        String text = loader.equals("fabric")
                ? "accessWidener v2 intermediary\nextendable method " + HOST_BEHAVIOR + " title ()Ljava/lang/String;\n"
                : "public-f " + HOST_BEHAVIOR.replace('/', '.') + " title()Ljava/lang/String; # title\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String metadataPath(String loader) {
        return loader.equals("fabric") ? "fabric.mod.json" : "META-INF/mods.toml";
    }

    private static byte[] metadata(String loader, String modId, boolean nestsLibrary) {
        String text = loader.equals("fabric")
                ? "{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0.0\","
                        + "\"depends\":{\"minecraft\":\"1.20.1\",\"fabricloader\":\">=0.14.0\"},"
                        + "\"accessWidener\":\"kitchen.accesswidener\""
                        + (nestsLibrary ? ",\"jars\":[{\"file\":\"META-INF/jars/lib.jar\"}]" : "") + "}"
                : "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""
                        + modId + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\n"
                        + "mandatory=true\nversionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Balm's shape: {@code result.getOrThrow(false, onError)} on DataFixerUpper 6's class. */
    private static byte[] codecs(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "decode",
                "(L" + DATA_RESULT + ";Ljava/util/function/Consumer;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DATA_RESULT, "getOrThrow",
                "(ZLjava/util/function/Consumer;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Farmer's Delight's SkilletItem: a static field typed as the Tiers enum, filled from Tiers.IRON, read through getUses. */
    private static byte[] skillet(String name, String loader) {
        boolean fabric = loader.equals("fabric");
        String tiers = fabric ? "net/minecraft/class_1834" : "net/minecraft/world/item/Tiers";
        String iron = fabric ? "field_8923" : "IRON";
        String getUses = fabric ? "method_8025" : "m_6609_";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "SKILLET_TIER", "L" + tiers + ";",
                null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(Opcodes.GETSTATIC, tiers, iron, "L" + tiers + ";");
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, name, "SKILLET_TIER", "L" + tiers + ";");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        MethodVisitor uses = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "uses", "()I", null, null);
        uses.visitCode();
        uses.visitFieldInsn(Opcodes.GETSTATIC, name, "SKILLET_TIER", "L" + tiers + ";");
        uses.visitMethodInsn(Opcodes.INVOKEVIRTUAL, tiers, getUses, "()I", false);
        uses.visitInsn(Opcodes.IRETURN);
        uses.visitMaxs(0, 0);
        uses.visitEnd();
        MethodVisitor first = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "firstTierName",
                "()Ljava/lang/String;", null, null);
        first.visitCode();
        first.visitMethodInsn(Opcodes.INVOKESTATIC, tiers, "values", "()[L" + tiers + ";", false);
        first.visitInsn(Opcodes.ICONST_0);
        first.visitInsn(Opcodes.AALOAD);
        first.visitMethodInsn(Opcodes.INVOKEVIRTUAL, tiers, "toString", "()Ljava/lang/String;", false);
        first.visitInsn(Opcodes.ARETURN);
        first.visitMaxs(0, 0);
        first.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** JEI's recipe shape: an override of a method the host made final, and a call to it through the mod's type. */
    private static byte[] behavior(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, HOST_BEHAVIOR, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, HOST_BEHAVIOR, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor label = cw.visitMethod(Opcodes.ACC_PUBLIC, "label", "()Ljava/lang/String;", null, null);
        label.visitCode();
        label.visitLdcInsn("mod");
        label.visitInsn(Opcodes.ARETURN);
        label.visitMaxs(0, 0);
        label.visitEnd();
        MethodVisitor own = cw.visitMethod(Opcodes.ACC_PUBLIC, "own", "()Ljava/lang/String;", null, null);
        own.visitCode();
        own.visitVarInsn(Opcodes.ALOAD, 0);
        own.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, "label", "()Ljava/lang/String;", false);
        own.visitInsn(Opcodes.ARETURN);
        own.visitMaxs(0, 0);
        own.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An override of the host's final title(), legal once the jar's access file opens it. */
    private static byte[] opened(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, HOST_BEHAVIOR, null);
        MethodVisitor title = cw.visitMethod(Opcodes.ACC_PUBLIC, "title", "()Ljava/lang/String;", null, null);
        title.visitCode();
        title.visitLdcInsn("mod");
        title.visitInsn(Opcodes.ARETURN);
        title.visitMaxs(0, 0);
        title.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry, className + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " lost " + name));
    }

    /** The one call a method makes, ignoring helpers the transform wraps around a static initializer. */
    private static MethodInsnNode onlyCall(MethodNode method) {
        List<MethodInsnNode> calls = calls(method);
        assertEquals(1, calls.size(), method.name + " calls " + calls.stream().map(c -> c.owner + "." + c.name).toList());
        return calls.get(0);
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && !call.owner.endsWith("/ForgeRegistryIdBridge")) calls.add(call);
        }
        return calls;
    }
}
