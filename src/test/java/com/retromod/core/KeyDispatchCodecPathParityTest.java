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
import com.retromod.shim.common.LegacyKeyDispatchCodecBridgeTest;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Every class-transform path repairs the 1.20.1 KeyDispatchDataCodec calls of TerraBlender, Lithostitched and Citadel, in the mod, its mixin and its nested library. */
class KeyDispatchCodecPathParityTest {

    private static final String MAP_CODEC_ACCESSOR = "()Lcom/mojang/serialization/MapCodec;";
    private static final String CODEC_ACCESSOR = "()Lcom/mojang/serialization/Codec;";
    private static final String HELPER = "com/retromod/generated/LegacyKeyDispatchCodec";
    private static final String STAND_IN = "com/retromod/generated/LegacyKeyDispatchDataCodec";
    private static final String L_MAP_CODEC = "Lcom/mojang/serialization/MapCodec;";
    private static final String KDDC = "net/minecraft/util/KeyDispatchDataCodec";
    private static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String LIFECYCLE = "Lcom/mojang/serialization/Lifecycle;";
    private static final String BOOTSTRAP = "Lnet/minecraft/core/registries/BuiltInRegistries$RegistryBootstrap;";
    private static final String LIFECYCLE_REGISTER = "(" + KEY + LIFECYCLE + BOOTSTRAP + ")L" + REGISTRY + ";";
    private static final String INTERNAL_REGISTER = "(" + KEY + "Lnet/minecraft/core/WritableRegistry;" + BOOTSTRAP
            + ")Lnet/minecraft/core/WritableRegistry;";
    private static final String CIR = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String SURFACE_RULE = "net/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule";
    private static final String CONTEXT = "net/minecraft/world/level/levelgen/SurfaceRules$Context";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private Field indexField;
    private FuzzyMethodResolver savedIndex;

    @TempDir
    Path dir;

    @AfterEach
    void restore() throws Exception {
        if (indexField != null) indexField.set(transformer, savedIndex);
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} {1} on {2}")
    @CsvSource({"forge, runtime, 26.2", "forge, runtime, 26.3", "forge, transform, 26.2", "forge, transform, 26.3",
            "forge, batch, 26.2", "forge, aot, 26.2", "forge, aot, 26.3", "neoforge, runtime, 26.2",
            "neoforge, transform, 26.3", "fabric, runtime, 26.2", "fabric, runtime, 26.3",
            "fabric, transform, 1.21.11", "fabric, aot, 26.3"})
    @DisplayName("each path registers the host's map codec and builds old codecs through the helper")
    void everyPathRepairsTheCodecCalls(String loader, String path, String host) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("terrablender.jar");
        Files.write(input, modJar(loader));

        Path output = translate(loader, path, host, input);

        String holder = holderFor(loader, host);
        for (Map.Entry<String, Map<String, ClassNode>> jar : classes(output, loader).entrySet()) {
            String where = jar.getKey() + " (" + loader + " " + path + " on " + host + ")";
            ClassNode mixin = jar.getValue().get("mixin");
            ClassNode rule = jar.getValue().get("rule");
            for (ClassNode node : jar.getValue().values()) {
                for (MethodNode method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
                assertFalse(readsOldAccessor(node), where + ": " + node.name + " still calls codec() for a Codec");
            }
            assertEquals(List.of("." + MAP_CODEC_ACCESSOR), holderReads(method(mixin, "lambda$registerSimple$0"), holder),
                    where + ": the registration takes the host's own map codec");
            assertTrue(helperCalls(method(mixin, "lambda$registerSimple$0")).isEmpty(),
                    where + ": mixin code merged into Minecraft calls only the host");
            assertEquals(List.of("codec"), helperCalls(method(rule, "read")), where);
            assertEquals(List.of("of"), helperCalls(method(rule, "<clinit>")), where);
            assertEquals(List.of("of"), helperCalls(method(rule, "create")), where);

            // Lithostitched's unit marker is built from the map codec itself.
            MethodNode marker = method(jar.getValue().get("marker"), "<clinit>");
            assertTrue(helperCalls(marker).isEmpty(), where + ": the marker's of(Codec.unit(x)) skips the helper");
            assertEquals(List.of("(" + L_MAP_CODEC + ")"), holderFactories(marker, holder), where);
            // Citadel's codec() method reference supplies the host's map codec to its registration.
            Handle supplied = (Handle) indy(method(jar.getValue().get("wrapper"), "registerAll")).bsmArgs[1];
            assertEquals(Opcodes.H_INVOKEVIRTUAL, supplied.getTag(), where);
            assertTrue(supplied.getOwner().endsWith(holder), where + ": " + supplied);
            assertEquals("()" + L_MAP_CODEC, supplied.getDesc(), where);
            // A rule source gets the codec() Minecraft calls, through a bridge interface in the mod.
            String bridge = accessorBridgeFor(host);
            List<String> interfaces = jar.getValue().get("rule-source").interfaces;
            if (bridge == null) {
                assertEquals(List.of(ruleSourceInterface(loader)), interfaces, where);
            } else {
                assertEquals(1, interfaces.size(), where);
                assertTrue(interfaces.get(0).startsWith(SyntheticEmbedder.PREFIX)
                        && interfaces.get(0).endsWith(bridge), where + ": " + interfaces);
                assertNotNull(jar.getValue().get("bridge"), where + ": the bridge interface is embedded");
            }
        }
    }

    @ParameterizedTest(name = "{0} {1}, the host lacks SurfaceRule: {2}")
    @CsvSource({"forge, runtime, true", "forge, runtime, false", "forge, transform, true", "forge, transform, false",
            "forge, batch, true", "forge, batch, false", "forge, aot, true", "forge, aot, false",
            "neoforge, runtime, true", "neoforge, runtime, false", "neoforge, transform, false",
            "fabric, runtime, true", "fabric, runtime, false", "fabric, transform, false"})
    @DisplayName("each path declines TerraBlender's Bootstrap repair when a class its lambda loads names a class the host lacks")
    void everyPathChecksWhatTheRepairedMixinLoads(String loader, String path, boolean removed) throws Exception {
        installHostIndex(removed);
        Path input = Files.createDirectories(dir.resolve("in")).resolve("terrablender.jar");
        Files.write(input, registryModJar(loader));

        Path output = translate(loader, path, "26.2", input);

        // 26.3 replaced SurfaceRule, which the rule class names: the mixin stays unapplied, as Mixin left it.
        String expected = removed ? "Lorg/spongepowered/asm/mixin/Shadow;" : "Lorg/spongepowered/asm/mixin/Unique;";
        try (JarFile jar = new JarFile(output.toFile())) {
            MethodNode outer = method(read(jar, "mod/MixinBuiltInRegistries"), "registerSimple", LIFECYCLE_REGISTER);
            assertTrue(hasAnnotation(outer, expected), loader + " " + path + ": the mod's mixin");
            Path nested = Files.createTempFile(dir, "nested-", ".jar");
            Files.write(nested, jar.getInputStream(jar.getJarEntry(nestedPath(loader))).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                MethodNode inner = method(read(lib, "lib/MixinBuiltInRegistries"), "registerSimple", LIFECYCLE_REGISTER);
                assertTrue(hasAnnotation(inner, expected), loader + " " + path + ": the nested library's mixin");
            }
        }
    }

    @ParameterizedTest(name = "{0} {1}, the host's rule type is an interface: {2}")
    @CsvSource({"forge, runtime, true", "forge, transform, true", "forge, aot, true", "forge, runtime, false",
            "neoforge, runtime, true", "fabric, runtime, true", "fabric, transform, false"})
    @DisplayName("on 26.3 the repair accepts a rule source whose old interface moved to the host's, only when the kinds match")
    void movedRuleInterfaceOn263(String loader, String path, boolean ruleIsInterface) throws Exception {
        installHostIndex263(ruleIsInterface);
        Path input = Files.createDirectories(dir.resolve("in")).resolve("terrablender.jar");
        Files.write(input, registryModJar(loader, true));

        Path output = translate(loader, path, "26.3", input);

        // A class cannot implement the host's rule type when the host made it a class.
        String expected = ruleIsInterface ? "Lorg/spongepowered/asm/mixin/Unique;" : "Lorg/spongepowered/asm/mixin/Shadow;";
        try (JarFile jar = new JarFile(output.toFile())) {
            MethodNode outer = method(read(jar, "mod/MixinBuiltInRegistries"), "registerSimple", LIFECYCLE_REGISTER);
            assertTrue(hasAnnotation(outer, expected), loader + " " + path + ": the mod's mixin");
        }
    }

    @ParameterizedTest(name = "on {0}, the second jar linked to the first: {1}")
    @CsvSource({"26.2, false", "26.2, true", "26.3, true"})
    @DisplayName("two translated jars whose rule sources implement the same interface load together and answer Minecraft's codec()")
    void twoJarsWithTheSameRuleInterfaceLoadTogether(String host, boolean linked) throws Exception {
        McReflect.setForceForge(true);
        ModVersionDetector.setDefaultPreferredLoader("forge");
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        registerForgeFamily("forge", host);
        String surfaceRule = "net/minecraft/world/level/levelgen/SurfaceRules$RuleSource";
        Path in = Files.createDirectories(dir.resolve("in"));
        Path firstOut = Files.createDirectories(dir.resolve("first"));
        Path secondOut = Files.createDirectories(dir.resolve("second"));

        // The first mod's rule source, and a second mod's that extends it and lists the interface again.
        Path first = Files.write(in.resolve("rulesa.jar"), LoaderApiFixtures.jar(Map.of(
                metadataPath("forge"), metadata("forge", "rulesa", false),
                "a/ARule.class", LegacyKeyDispatchCodecBridgeTest.ruleSourceImpl("a/ARule", surfaceRule, "L" + KDDC + ";",
                        "codec"))));
        Path second = Files.write(in.resolve("rulesb.jar"), LoaderApiFixtures.jar(Map.of(
                metadataPath("forge"), metadata("forge", "rulesb", false),
                "b/BRule.class", subclass("b/BRule", "a/ARule", surfaceRule))));
        Path firstJar = new ForgeModTransformer(host).transformMod(first, firstOut);
        Path secondJar = new ForgeModTransformer(host).transformMod(second, secondOut,
                linked ? SyntheticLibraries.ofFolder(firstOut) : SyntheticLibraries.NONE);

        // Both jars in one loader, as the game loads them, over the host's rule interface.
        String hostInterface = RetromodVersion.compareMcVersions(host, "26.3") >= 0
                ? "net/minecraft/world/level/levelgen/material/rule/MaterialRule" : surfaceRule;
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.putAll(jarClasses(secondJar));
        classes.putAll(jarClasses(firstJar));
        classes.put(hostInterface, LegacyKeyDispatchCodecBridgeTest.hostRuleInterface(hostInterface));
        classes.put(KDDC, LegacyKeyDispatchCodecBridgeTest.hostKeyDispatchCodec());
        classes.put("com/mojang/serialization/MapCodec",
                LegacyKeyDispatchCodecBridgeTest.emptyClass("com/mojang/serialization/MapCodec"));
        ClassLoader loader = new LegacyKeyDispatchCodecBridgeTest.ChildFirstLoader(new java.net.URL[0], classes);
        Object mapCodec = loader.loadClass("com.mojang.serialization.MapCodec").getConstructor().newInstance();
        Class<?> base = loader.loadClass("a.ARule");
        Class<?> holder = base.getField("CODEC").getType();
        base.getField("CODEC").set(null, holder.getMethod("of", mapCodec.getClass()).invoke(null, mapCodec));

        java.lang.reflect.Method hostCodec = loader.loadClass(hostInterface.replace('/', '.')).getMethod("codec");
        for (String name : List.of("a.ARule", "b.BRule")) {
            Object rule = loader.loadClass(name).getConstructor().newInstance();
            try {
                assertSame(mapCodec, hostCodec.invoke(rule), name);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new AssertionError(name + ": Minecraft's codec() failed", e.getCause());
            }
        }
    }

    /** {@code public class name extends superName implements iface}, with only a constructor. */
    private static byte[] subclass(String name, String superName, String iface) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, superName, new String[]{iface});
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Every class in a jar, by internal name. */
    private static Map<String, byte[]> jarClasses(Path jarPath) throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (JarEntry entry : java.util.Collections.list(jar.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                String name = entry.getName().substring(0, entry.getName().length() - ".class".length());
                classes.put(name, jar.getInputStream(entry).readAllBytes());
            }
        }
        return classes;
    }

    /** The bridge interface a rule source gets on {@code host}, or null where Minecraft's codec() still returns the holder. */
    private static String accessorBridgeFor(String host) {
        if (RetromodVersion.compareMcVersions(host, "26.3") >= 0) return "com/retromod/generated/LegacyMaterialRuleCodec";
        if (RetromodVersion.compareMcVersions(host, "26.2") >= 0) return "com/retromod/generated/LegacyRuleSourceCodec";
        return null;
    }

    private static String ruleSourceInterface(String loader) {
        return loader.equals("fabric") ? "net/minecraft/class_6686$class_6708"
                : "net/minecraft/world/level/levelgen/SurfaceRules$RuleSource";
    }

    /** The descriptors of the holder's of() calls, without their return type. */
    private static List<String> holderFactories(MethodNode method, String holder) {
        List<String> factories = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.endsWith(holder)
                    && (call.name.equals("of") || call.name.equals("method_42116"))) {
                factories.add(call.desc.substring(0, call.desc.indexOf(')') + 1));
            }
        }
        return factories;
    }

    private static InvokeDynamicInsnNode indy(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) return indy;
        }
        throw new AssertionError(method.name + " has no invokedynamic");
    }

    /** The class whose accessor the registration calls: the host's, or the 26.3 stand-in. */
    private static String holderFor(String loader, String host) {
        if (!RetromodVersion.isUnobfuscatedTarget(host) && loader.equals("fabric")) return "net/minecraft/class_7243";
        return RetromodVersion.compareMcVersions(host, "26.3") >= 0 ? STAND_IN : "net/minecraft/util/KeyDispatchDataCodec";
    }

    private static List<String> holderReads(MethodNode method, String holder) {
        List<String> reads = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.endsWith(holder)
                    && (call.name.equals("codec") || call.name.equals("comp_640"))) {
                reads.add("." + call.desc);
            }
        }
        return reads;
    }

    private static boolean readsOldAccessor(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.desc.equals(CODEC_ACCESSOR)
                        && (call.name.equals("codec") || call.name.equals("comp_640"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The helper methods called, which the embedder moved under the mod's own package. */
    private static List<String> helperCalls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.endsWith(HELPER)) {
                assertTrue(call.owner.startsWith(SyntheticEmbedder.PREFIX), "the helper is embedded: " + call.owner);
                calls.add(call.name);
            }
        }
        return calls;
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
                CliHarness.transform(input, out.resolve("terrablender.jar"), host);
                yield out.resolve("terrablender.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("terrablender.jar"), host);
                yield out.resolve("terrablender.jar");
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
        registerForgeFamily(loader, host);
        return new ForgeModTransformer(host).transformMod(input, out);
    }

    /** Registers what the Forge or NeoForge entry point registers on {@code host}. */
    private void registerForgeFamily(String loader, String host) {
        Set<String> types = loader.equals("forge") ? Set.of("forge", "common") : Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
    }

    /** The fixture classes of the mod and of its nested library, as the output jar holds them. */
    private static Map<String, Map<String, ClassNode>> classes(Path output, String loader) throws Exception {
        Map<String, Map<String, ClassNode>> found = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            found.put("mod", fixtureNodes(jar, "mod"));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                JarEntry entry = jar.getJarEntry(nestedPath(loader));
                assertNotNull(entry, "the nested library is missing from " + output.getFileName());
                Files.write(nested, jar.getInputStream(entry).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    found.put("nested", fixtureNodes(lib, "lib"));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    /** One jar's fixture classes, and the interface its rule source implements when the jar holds it. */
    private static Map<String, ClassNode> fixtureNodes(JarFile jar, String pkg) throws Exception {
        Map<String, ClassNode> nodes = new LinkedHashMap<>();
        nodes.put("mixin", read(jar, pkg + "/MixinBuiltInRegistries"));
        nodes.put("rule", read(jar, pkg + "/RuleSource"));
        nodes.put("marker", read(jar, pkg + "/Marker"));
        nodes.put("wrapper", read(jar, pkg + "/Wrapper"));
        ClassNode ruleSource = read(jar, pkg + "/Rule");
        nodes.put("rule-source", ruleSource);
        if (!ruleSource.interfaces.isEmpty() && jar.getJarEntry(ruleSource.interfaces.get(0) + ".class") != null) {
            nodes.put("bridge", read(jar, ruleSource.interfaces.get(0)));
        }
        return nodes;
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
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name));
    }

    private static String nestedPath(String loader) {
        return loader.equals("fabric") ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    /** A 1.20.1 mod for {@code loader} (1.20.4 for NeoForge) with the two classes, and a nested library with the same. */
    private static byte[] modJar(String loader) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put(metadataPath(loader), metadata(loader, "terralib", false));
        lib.putAll(fixtureClasses("lib", loader));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(metadataPath(loader), metadata(loader, "terrablender", true));
        mod.putAll(fixtureClasses("mod", loader));
        mod.put(nestedPath(loader), LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    /** TerraBlender's mixin and rule source in {@code loader}'s names: intermediary for Fabric, Mojang otherwise. */
    private static Map<String, byte[]> fixtureClasses(String pkg, String loader) {
        boolean fabric = loader.equals("fabric");
        String kddc = fabric ? "Lnet/minecraft/class_7243;" : "Lnet/minecraft/util/KeyDispatchDataCodec;";
        String registry = fabric ? "net/minecraft/class_2378" : "net/minecraft/core/Registry";
        String id = fabric ? "net/minecraft/class_2960" : "net/minecraft/resources/ResourceLocation";
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(pkg + "/MixinBuiltInRegistries.class",
                LegacyKeyDispatchCodecBridgeTest.terraBlenderMixin(pkg + "/MixinBuiltInRegistries", kddc, registry, id));
        classes.put(pkg + "/RuleSource.class", LegacyKeyDispatchCodecBridgeTest.surfaceRule(pkg + "/RuleSource", kddc));
        classes.put(pkg + "/Marker.class", LegacyKeyDispatchCodecBridgeTest.unitMarker(pkg + "/Marker", kddc, "value"));
        classes.put(pkg + "/Wrapper.class",
                LegacyKeyDispatchCodecBridgeTest.citadelRegistration(pkg + "/Wrapper", kddc));
        classes.put(pkg + "/Rule.class", LegacyKeyDispatchCodecBridgeTest.ruleSourceImpl(pkg + "/Rule",
                ruleSourceInterface(loader), kddc, fabric ? "method_39061" : "codec"));
        if (fabric) classes.put(pkg + ".mixins.json", mixinConfig(pkg));
        return classes;
    }

    /** The Fabric mixin config that names the package's registry mixin. */
    private static byte[] mixinConfig(String pkg) {
        return ("{\"required\":true,\"package\":\"" + pkg + "\",\"mixins\":[\"MixinBuiltInRegistries\"]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    // ---- the Bootstrap repair's check ----

    /** Installs a 26.2 host index with what TerraBlender's repaired mixin calls, and SurfaceRule unless it was {@code removed}. */
    private void installHostIndex(boolean removed) throws Exception {
        Path jar = Files.createTempFile(dir, "host-", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, hostClass(BUILT_IN_REGISTRIES, "java/lang/Object", 0, writer -> {
                hostMethod(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "registerSimple",
                        "(" + KEY + BOOTSTRAP + ")L" + REGISTRY + ";");
                hostMethod(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "internalRegister", INTERNAL_REGISTER);
            }));
            put(out, hostClass("net/minecraft/core/MappedRegistry", "java/lang/Object", 0,
                    writer -> hostMethod(writer, Opcodes.ACC_PUBLIC, "<init>", "(" + KEY + LIFECYCLE + "Z)V")));
            put(out, hostClass(KDDC, "java/lang/Record", Opcodes.ACC_FINAL,
                    writer -> hostMethod(writer, Opcodes.ACC_PUBLIC, "codec", "()" + L_MAP_CODEC)));
            put(out, hostClass(REGISTRY, "java/lang/Object", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                    writer -> hostMethod(writer, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register",
                            "(L" + REGISTRY + ";Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)Ljava/lang/Object;")));
            for (String name : List.of("net/minecraft/resources/Identifier", "net/minecraft/resources/ResourceKey",
                    "net/minecraft/core/registries/BuiltInRegistries$RegistryBootstrap")) {
                put(out, hostClass(name, "java/lang/Object", 0, writer -> { }));
            }
            if (!removed) {
                put(out, hostClass(SURFACE_RULE, "java/lang/Object", 0, writer -> { }));
                put(out, hostClass(CONTEXT, "java/lang/Object", 0, writer -> { }));
            }
        }
        FuzzyMethodResolver index = new FuzzyMethodResolver();
        index.indexJar(jar);
        indexField = RetromodTransformer.class.getDeclaredField("fuzzyResolver");
        indexField.setAccessible(true);
        savedIndex = (FuzzyMethodResolver) indexField.get(transformer);
        indexField.set(transformer, index);
    }

    /** Installs a 26.3 host index: the registry bootstrap, and the material rule types the old surface rule types move to. */
    private void installHostIndex263(boolean ruleIsInterface) throws Exception {
        Path jar = Files.createTempFile(dir, "host-", ".jar");
        String material = "net/minecraft/world/level/levelgen/material/";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, hostClass(BUILT_IN_REGISTRIES, "java/lang/Object", 0, writer -> {
                hostMethod(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "registerSimple",
                        "(" + KEY + BOOTSTRAP + ")L" + REGISTRY + ";");
                hostMethod(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "internalRegister", INTERNAL_REGISTER);
            }));
            put(out, hostClass("net/minecraft/core/MappedRegistry", "java/lang/Object", 0,
                    writer -> hostMethod(writer, Opcodes.ACC_PUBLIC, "<init>", "(" + KEY + LIFECYCLE + "Z)V")));
            put(out, hostClass(REGISTRY, "java/lang/Object", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                    writer -> hostMethod(writer, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register",
                            "(L" + REGISTRY + ";Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)Ljava/lang/Object;")));
            for (String name : List.of("net/minecraft/resources/Identifier", "net/minecraft/resources/ResourceKey",
                    "net/minecraft/core/registries/BuiltInRegistries$RegistryBootstrap", material + "MaterialRuleContext")) {
                put(out, hostClass(name, "java/lang/Object", 0, writer -> { }));
            }
            put(out, hostClass(material + "rule/RuleEvaluator", "java/lang/Object",
                    Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, writer -> { }));
            put(out, hostClass(material + "rule/MaterialRule", "java/lang/Object",
                    ruleIsInterface ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0, writer -> { }));
        }
        FuzzyMethodResolver index = new FuzzyMethodResolver();
        index.indexJar(jar);
        indexField = RetromodTransformer.class.getDeclaredField("fuzzyResolver");
        indexField.setAccessible(true);
        savedIndex = (FuzzyMethodResolver) indexField.get(transformer);
        indexField.set(transformer, index);
    }

    /** A 1.20.1 mod and nested library with TerraBlender's registry mixin and the rule class its lambda reads. */
    private static byte[] registryModJar(String loader) throws Exception {
        return registryModJar(loader, false);
    }

    /** The same, with the rule class implementing the old rule interface when {@code ruleSource}. */
    private static byte[] registryModJar(String loader, boolean ruleSource) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put(metadataPath(loader), metadata(loader, "terralib", false));
        lib.putAll(registryClasses("lib", loader, ruleSource));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(metadataPath(loader), metadata(loader, "terrablender", true));
        mod.putAll(registryClasses("mod", loader, ruleSource));
        mod.put(nestedPath(loader), LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    /** The mixin, the rule class, and for Fabric the mixin config that names the mixin. */
    private static Map<String, byte[]> registryClasses(String pkg, String loader, boolean ruleSource) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(pkg + "/MixinBuiltInRegistries.class", registryMixin(pkg));
        classes.put(pkg + "/NamespacedRule.class", namespacedRule(pkg, ruleSource));
        if (loader.equals("fabric")) classes.put(pkg + ".mixins.json", mixinConfig(pkg));
        return classes;
    }

    /**
     * TerraBlender 3.0.1.11's {@code MixinBuiltInRegistries}: a shadow of the lifecycle overload
     * 26.2 removed, a handler at the head of the kept one that calls it, and the registration lambda.
     */
    private static byte[] registryMixin(String pkg) {
        String name = pkg + "/MixinBuiltInRegistries";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER, name, null,
                "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(BUILT_IN_REGISTRIES));
        targets.visitEnd();
        mixin.visitEnd();

        MethodVisitor shadow = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "registerSimple",
                LIFECYCLE_REGISTER, null, null);
        shadow.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", true).visitEnd();
        shadow.visitCode();
        shadow.visitInsn(Opcodes.ACONST_NULL);
        shadow.visitInsn(Opcodes.ARETURN);
        shadow.visitMaxs(0, 0);
        shadow.visitEnd();

        MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "registerSimple",
                "(" + KEY + BOOTSTRAP + "L" + CIR + ";)V", null, null);
        AnnotationVisitor inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, "registerSimple(" + KEY + BOOTSTRAP + ")L" + REGISTRY + ";");
        methods.visitEnd();
        AnnotationVisitor ats = inject.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "HEAD");
        at.visitEnd();
        ats.visitEnd();
        inject.visit("require", 0);
        inject.visitEnd();
        handler.visitCode();
        handler.visitVarInsn(Opcodes.ALOAD, 2);
        handler.visitVarInsn(Opcodes.ALOAD, 0);
        handler.visitMethodInsn(Opcodes.INVOKESTATIC, "com/mojang/serialization/Lifecycle", "stable",
                "()" + LIFECYCLE, false);
        handler.visitVarInsn(Opcodes.ALOAD, 1);
        handler.visitMethodInsn(Opcodes.INVOKESTATIC, name, "registerSimple", LIFECYCLE_REGISTER, false);
        handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CIR, "setReturnValue", "(Ljava/lang/Object;)V", false);
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();

        // return Registry.register(registry, null, NamespacedRule.CODEC.codec());
        MethodVisitor lambda = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$registerSimple$0", "(L" + REGISTRY + ";)Ljava/lang/Object;", null, null);
        lambda.visitCode();
        lambda.visitVarInsn(Opcodes.ALOAD, 0);
        lambda.visitInsn(Opcodes.ACONST_NULL);
        lambda.visitFieldInsn(Opcodes.GETSTATIC, pkg + "/NamespacedRule", "CODEC", "L" + KDDC + ";");
        lambda.visitMethodInsn(Opcodes.INVOKEVIRTUAL, KDDC, "codec", "()Lcom/mojang/serialization/Codec;", false);
        lambda.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register",
                "(L" + REGISTRY + ";Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Object;)Ljava/lang/Object;", true);
        lambda.visitInsn(Opcodes.ARETURN);
        lambda.visitMaxs(0, 0);
        lambda.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** TerraBlender's rule class as its jar holds it: its codec, a rule built for 26.2's SurfaceRule, and the old interface when {@code ruleSource}. */
    private static byte[] namespacedRule(String pkg, boolean ruleSource) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, pkg + "/NamespacedRule", null, "java/lang/Object",
                ruleSource ? new String[]{"net/minecraft/world/level/levelgen/SurfaceRules$RuleSource"} : null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CODEC", "L" + KDDC + ";", null, null)
                .visitEnd();
        hostMethod(cw, Opcodes.ACC_PUBLIC, "apply", "(L" + CONTEXT + ";)L" + SURFACE_RULE + ";");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] hostClass(String name, String superName, int access, Consumer<ClassWriter> members) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | access, name, null, superName, null);
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A method with a body that throws, which is all an index or a test fixture needs. */
    private static void hostMethod(ClassWriter writer, int access, String name, String desc) {
        MethodVisitor body = writer.visitMethod(access, name, desc, null, null);
        if ((access & Opcodes.ACC_ABSTRACT) == 0) {
            body.visitCode();
            body.visitInsn(Opcodes.ACONST_NULL);
            body.visitInsn(Opcodes.ATHROW);
            body.visitMaxs(1, Type.getArgumentsAndReturnSizes(desc) >> 2);
        }
        body.visitEnd();
    }

    private static void put(JarOutputStream out, byte[] classBytes) throws Exception {
        out.putNextEntry(new JarEntry(new ClassReader(classBytes).getClassName() + ".class"));
        out.write(classBytes);
        out.closeEntry();
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name + desc));
    }

    private static boolean hasAnnotation(MethodNode method, String desc) {
        List<AnnotationNode> all = new ArrayList<>();
        if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);
        return all.stream().anyMatch(annotation -> desc.equals(annotation.desc));
    }

    private static String metadataPath(String loader) {
        return loader.equals("fabric") ? "fabric.mod.json" : "META-INF/mods.toml";
    }

    private static byte[] metadata(String loader, String modId, boolean nestsLibrary) {
        String text = switch (loader) {
            case "fabric" -> "{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0.0\","
                    + "\"depends\":{\"minecraft\":\"1.20.1\",\"fabricloader\":\">=0.14.0\"},"
                    + "\"mixins\":[\"" + (nestsLibrary ? "mod" : "lib") + ".mixins.json\"]"
                    + (nestsLibrary ? ",\"jars\":[{\"file\":\"META-INF/jars/lib.jar\"}]" : "") + "}";
            case "forge" -> "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""
                    + modId + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\n"
                    + "mandatory=true\nversionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
            default -> "modLoader=\"javafml\"\nloaderVersion=\"[2,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""
                    + modId + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"neoforge\"\n"
                    + "type=\"required\"\nversionRange=\"[20.4,)\"\nordering=\"NONE\"\nside=\"BOTH\"\n"
                    + "[[dependencies." + modId + "]]\nmodId=\"minecraft\"\n"
                    + "type=\"required\"\nversionRange=\"[1.20.4,1.20.5)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
        };
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
