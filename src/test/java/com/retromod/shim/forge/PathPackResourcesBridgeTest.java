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
import com.retromod.mixin.MixinBlocklist;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old mods build, extend, test for and read Forge's folder pack on Forge 26.2 and 26.3, where it is gone. */
class PathPackResourcesBridgeTest {

    private static final String FORGE_PACK = LegacyPathPackResourcesBridge.FORGE_PACK;
    private static final String VANILLA_PACK = LegacyPathPackResourcesBridge.VANILLA_PACK;
    private static final String REBUILT = LegacyPathPackResourcesBridge.REBUILT;
    private static final String OLD_CTOR = LegacyPathPackResourcesBridge.OLD_CTOR;
    private static final String RESOLVE = LegacyPathPackResourcesBridge.RESOLVE;
    private static final String VANILLA_1201_CTOR = "(Ljava/lang/String;Ljava/nio/file/Path;Z)V";
    private static final String PATH = "java/nio/file/Path";
    private static final String GET_SOURCE = "()L" + PATH + ";";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String PACK_TYPE = "net/minecraft/server/packs/PackType";
    private static final String IO_SUPPLIER = "net/minecraft/server/packs/resources/IoSupplier";
    private static final String GET_RESOURCE = "(L" + PACK_TYPE + ";L" + IDENTIFIER + ";)L" + IO_SUPPLIER + ";";
    private static final String ROOT_RESOURCE = "([Ljava/lang/String;)L" + IO_SUPPLIER + ";";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String MODERNFIX_MIXIN =
            "org/embeddedt/modernfix/common/mixin/perf/resourcepacks/ForgePathPackResourcesMixin";
    private static final String FUSION_MIXIN = "com/supermartijn642/fusion/mixin/forge/ForgePathPackResourcesMixin";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forge262() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
        resetBlocklist();
    }

    @AfterEach
    void restore() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
        resetBlocklist();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge 26.2 shim sends the Forge pack to the vanilla one and rebases its subclasses")
    void shimRegistersTheBridge(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        assertEquals(VANILLA_PACK, transformer.getClassRedirects().get(FORGE_PACK));
        assertTrue(transformer.getSyntheticClasses().containsKey(REBUILT), "the rebuilt base travels with the mod");
        assertTrue(transformer.getSuperclassRebaseSources().contains(VANILLA_PACK));
    }

    @Test
    @DisplayName("Forge 26.1, NeoForge, and a run with no Forge target keep the Forge pack name")
    void otherTargetsAreLeftAlone() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        LegacyPathPackResourcesBridge.register(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(FORGE_PACK), "Forge 26.1");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        LegacyPathPackResourcesBridge.register(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(FORGE_PACK), "NeoForge");

        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(false);
        LegacyPathPackResourcesBridge.register(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(FORGE_PACK), "Fabric, Quilt or an unnamed loader");
        assertFalse(transformer.getSyntheticClasses().containsKey(REBUILT));
    }

    @Test
    @DisplayName("new, super(...), instanceof, checkcast and getSource map onto the vanilla pack and the rebuilt base")
    void callSitesMapOntoTheVanillaPack() {
        LegacyPathPackResourcesBridge.register(transformer);
        ClassNode nested = node(transformer.transformClass(nestedPack(), "mod/NestedPack"));
        ClassNode builder = node(transformer.transformClass(builder(), "mod/Builder"));
        ClassNode cache = node(transformer.transformClass(vanillaPack(), "mod/CachePack"));

        assertEquals(REBUILT, nested.superName, "an old subclass extends the rebuilt base");
        assertEquals(List.of("INVOKESPECIAL " + REBUILT + ".<init>" + OLD_CTOR),
                calls(method(nested, "<init>"), REBUILT), "super(id, builtin, source) keeps its arguments");
        assertEquals(List.of("INVOKESTATIC " + REBUILT + ".superSourceOf(L" + VANILLA_PACK + ";)L" + PATH + ";"),
                calls(method(nested, "getSource"), REBUILT), "super.getSource() never runs the override again");

        assertEquals(REBUILT, cache.superName, "a subclass of the vanilla pack is rebased too");
        assertEquals(List.of("INVOKESPECIAL " + REBUILT + ".<init>" + VANILLA_1201_CTOR),
                calls(method(cache, "<init>"), REBUILT), "vanilla 1.20.1's constructor goes to the rebuilt base");

        assertEquals(List.of("INVOKESTATIC " + REBUILT + ".create(Ljava/lang/String;ZL" + PATH + ";)L" + VANILLA_PACK + ";"),
                calls(method(builder, "build"), REBUILT));
        assertEquals(List.of("INSTANCEOF " + VANILLA_PACK, "CHECKCAST " + VANILLA_PACK),
                typeInsns(method(builder, "sourceOf")), "a pack Forge 26.2 built is a vanilla PathPackResources");
        assertEquals(List.of("INVOKESTATIC " + REBUILT + ".sourceOf(L" + VANILLA_PACK + ";)L" + PATH + ";"),
                calls(method(builder, "sourceOf"), REBUILT));
        assertEquals(List.of("CHECKCAST " + VANILLA_PACK), typeInsns(method(builder, "rootMcmeta")));

        for (ClassNode translated : List.of(nested, builder)) {
            String text = new String(write(translated), StandardCharsets.ISO_8859_1);
            assertFalse(text.contains(FORGE_PACK), translated.name + " still names Forge's removed class");
        }
    }

    @Test
    @DisplayName("Old packs read their files the Forge 1.20.1 way, and host packs answer getSource")
    void oldPacksReadTheirFiles(@TempDir Path dir) throws Exception {
        LegacyPathPackResourcesBridge.register(transformer);
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("mod/NestedPack", transformer.transformClass(nestedPack(), "mod/NestedPack"));
        classes.put("mod/FlatPack", transformer.transformClass(flatPack(), "mod/FlatPack"));
        classes.put("mod/Builder", transformer.transformClass(builder(), "mod/Builder"));
        classes.put("mod/CachePack", transformer.transformClass(vanillaPack(), "mod/CachePack"));
        ClassLoader loader = hostStandIns().loader(dir.resolve("host"), transformer, classes);
        Host host = new Host(loader);

        Path plain = write(dir.resolve("plain/pack.mcmeta"), "plain meta").getParent();
        Path outer = dir.resolve("outer");
        write(outer.resolve("inner/pack.mcmeta"), "inner meta");
        write(outer.resolve("inner/data/testns/thing.json"), "thing");
        write(outer.resolve("inner/assets/testns/lang/en_us.json"), "lang");
        write(outer.resolve("data/testns/wrong.json"), "outside the inner folder");
        Path hostRoot = write(dir.resolve("hostpack/pack.mcmeta"), "host meta").getParent();

        // new PathPackResources(id, true, folder): a vanilla pack named and sourced the way Forge did it.
        Class<?> builder = loader.loadClass("mod.Builder");
        Object built = builder.getMethod("build", String.class, Path.class).invoke(null, "plain", plain);
        assertTrue(host.pathPack.isInstance(built), "the built pack is a vanilla PathPackResources");
        Object location = host.call(built, "location");
        assertEquals("plain", host.call(location, "id"));
        assertEquals("plain", host.call(host.call(location, "title"), "getString"));
        assertSame(host.packSource("BUILT_IN"), host.call(location, "source"));
        assertEquals(plain, builder.getMethod("sourceOf", Object.class).invoke(null, built));
        assertEquals("plain meta", host.read(builder.getMethod("rootMcmeta", Object.class).invoke(null, built)));

        // A pack the host built answers getSource from its own folder.
        Object hostPack = host.newVanillaPack("hostpack", hostRoot);
        assertEquals(hostRoot, builder.getMethod("sourceOf", Object.class).invoke(null, hostPack));
        assertEquals("host meta", host.read(builder.getMethod("rootMcmeta", Object.class).invoke(null, hostPack)));

        // An old subclass that overrides resolve reads every file through it, as Forge's base did.
        Object nested = loader.loadClass("mod.NestedPack").getConstructor(String.class, Path.class, String.class)
                .newInstance("nested", outer, "inner");
        assertEquals("inner meta", host.read(host.rootResource(nested, "pack.mcmeta")));
        assertEquals("thing", host.read(host.resource(nested, "SERVER_DATA", "testns", "thing.json")));
        assertEquals("lang", host.read(host.resource(nested, "SERVER_DATA", "testns", "lang/en_us.json")),
                "Forge read lang files from assets for every pack type");
        assertEquals(Set.of("testns"), host.namespaces(nested, "SERVER_DATA"));
        assertEquals(Set.of("testns:thing.json"), host.list(nested, "SERVER_DATA", "testns"));
        assertEquals(outer, builder.getMethod("sourceOf", Object.class).invoke(null, nested),
                "getSource through an override that calls super.getSource()");

        // A subclass that only calls the inherited resolve finds it on the rebuilt base.
        Path flat = dir.resolve("flat");
        Object flatPack = loader.loadClass("mod.FlatPack").getConstructor(String.class, boolean.class, Path.class)
                .newInstance("flat", false, flat);
        assertEquals(flat.resolve("data"), flatPack.getClass().getMethod("dataFolder").invoke(flatPack));
        assertSame(host.packSource("DEFAULT"), host.call(host.call(flatPack, "location"), "source"));

        // Like Moonlight's DynamicPackCache: vanilla 1.20.1's constructor, then vanilla's own reading.
        Object cache = loader.loadClass("mod.CachePack").getConstructor(String.class, Path.class, boolean.class)
                .newInstance("cache", plain, true);
        assertTrue(host.pathPack.isInstance(cache));
        assertEquals("cache", host.call(host.call(cache, "location"), "id"));
        assertSame(host.packSource("BUILT_IN"), host.call(host.call(cache, "location"), "source"));
        assertEquals("plain meta", host.read(host.rootResource(cache, "pack.mcmeta")));
    }

    @Test
    @DisplayName("The two mixins on Forge's pack are disabled from Minecraft 26.2 and kept before it")
    void forgePackMixinsAreBlocklisted() {
        for (String target : List.of("26.2", "26.3")) {
            RetromodVersion.TARGET_MC_VERSION = target;
            assertTrue(MixinBlocklist.isFullStrip(MODERNFIX_MIXIN), "ModernFix on " + target);
            assertTrue(MixinBlocklist.isFullStrip(FUSION_MIXIN), "Fusion on " + target);
        }
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        assertFalse(MixinBlocklist.isFullStrip(MODERNFIX_MIXIN));
        assertFalse(MixinBlocklist.isFullStrip(FUSION_MIXIN));
    }

    @Test
    @DisplayName("A mixin on Forge's pack never lands on the vanilla pack the redirect points at")
    void forgePackMixinIsNeutralized() {
        LegacyPathPackResourcesBridge.register(transformer);
        MixinCompatibilityTransformer mixins = new MixinCompatibilityTransformer(transformer);
        byte[] prepared = mixins.transformMixinClass(forgePackMixin(MODERNFIX_MIXIN));
        byte[] translated = transformer.transformClass(prepared, MODERNFIX_MIXIN);
        assertEquals(List.of("targets=[retromod/stripped/ForgePathPackResourcesMixin]"), mixinTargets(node(translated)));
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI batch and AOT paths translate packs the same way, outer and nested")
    void everyPathTranslatesTheSame(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("packs.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("packs.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        String rebuilt = "embedded " + REBUILT;
        Map<String, String> expected = new TreeMap<>(Map.of(
                "mod/NestedPack", "extends " + rebuilt,
                "mod/Builder.build", "INVOKESTATIC " + rebuilt + ".create",
                "mod/Builder.sourceOf", "INSTANCEOF " + VANILLA_PACK + ", INVOKESTATIC " + rebuilt + ".sourceOf",
                "mixin " + MODERNFIX_MIXIN, "targets=[retromod/stripped/ForgePathPackResourcesMixin]",
                "nested:mod/FlatPack", "extends " + rebuilt));
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey());
        }
    }

    // What RetromodForge.loadForgeShims registers on the host, then one runtime transform.
    private Path forgeRuntime(Path input, Path dir, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        Path out = new ForgeModTransformer(host).transformMod(input, Files.createDirectory(dir.resolve("runtime")));
        assertNotNull(out, "the Forge runtime wrote a jar");
        return out;
    }

    private static Map<String, String> summary(Path jar) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            describeBase(file, "mod/NestedPack", "", found);
            ClassNode builder = node(read(file, "mod/Builder"));
            found.put("mod/Builder.build", String.join(", ", embeddedCalls(file, method(builder, "build"))));
            List<String> sourceOf = new ArrayList<>(typeInsns(method(builder, "sourceOf")).subList(0, 1));
            sourceOf.addAll(embeddedCalls(file, method(builder, "sourceOf")));
            found.put("mod/Builder.sourceOf", String.join(", ", sourceOf));
            found.put("mixin " + MODERNFIX_MIXIN, String.join(", ", mixinTargets(node(read(file, MODERNFIX_MIXIN)))));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    describeBase(lib, "mod/FlatPack", "nested:", found);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    // The superclass, which must be a rebuilt base embedded in the same jar.
    private static void describeBase(JarFile jar, String name, String prefix, Map<String, String> found) throws Exception {
        String base = node(read(jar, name)).superName;
        found.put(prefix + name, "extends " + embeddedName(jar, base));
    }

    // Calls into an embedded rebuilt class, named by the rebuilt class they started as.
    private static List<String> embeddedCalls(JarFile jar, MethodNode method) {
        List<String> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.contains("com/retromod/legacyapi/")) {
                found.add(opcode(call.getOpcode()) + " " + embeddedName(jar, call.owner) + "." + call.name);
            }
        }
        return found;
    }

    private static String embeddedName(JarFile jar, String name) {
        int rebuilt = name.indexOf("com/retromod/legacyapi/");
        if (rebuilt < 0) return name;
        boolean embedded = name.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(name + ".class") != null;
        return (embedded ? "embedded " : "") + name.substring(rebuilt);
    }

    // A Forge 1.20.1 mod with an old pack subclass, a pack builder, a ModernFix-named mixin and a nested library.
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", toml("fixturelib"));
        lib.put("mod/FlatPack.class", flatPack());
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", toml("packmod"));
        mod.put("mod/NestedPack.class", nestedPack());
        mod.put("mod/Builder.class", builder());
        mod.put(MODERNFIX_MIXIN + ".class", forgePackMixin(MODERNFIX_MIXIN));
        mod.put("packmod.mixins.json", ("{\"package\":\"org.embeddedt.modernfix.common.mixin\",\"mixins\":"
                + "[\"perf.resourcepacks.ForgePathPackResourcesMixin\"]}").getBytes(StandardCharsets.UTF_8));
        mod.put("META-INF/jarjar/lib.jar", jar(lib));
        return jar(mod);
    }

    /** Like Create's and Tacz's packs: fields set before super, an inner-folder resolve, and super-calling overrides. */
    static byte[] nestedPack() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/NestedPack", null, FORGE_PACK, null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "inner", "Ljava/lang/String;", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;L" + PATH + ";Ljava/lang/String;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 3);
        init.visitFieldInsn(PUTFIELD, "mod/NestedPack", "inner", "Ljava/lang/String;");
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitInsn(ICONST_1);
        init.visitVarInsn(ALOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, FORGE_PACK, "<init>", OLD_CTOR, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // protected Path resolve(String... paths): super.getSource().resolve(inner), then each segment.
        MethodVisitor resolve = cw.visitMethod(ACC_PROTECTED | ACC_VARARGS, "resolve", RESOLVE, null, null);
        resolve.visitCode();
        resolve.visitVarInsn(ALOAD, 0);
        resolve.visitMethodInsn(INVOKESPECIAL, FORGE_PACK, "getSource", GET_SOURCE, false);
        resolve.visitVarInsn(ALOAD, 0);
        resolve.visitFieldInsn(GETFIELD, "mod/NestedPack", "inner", "Ljava/lang/String;");
        resolve.visitMethodInsn(INVOKEINTERFACE, PATH, "resolve", "(Ljava/lang/String;)L" + PATH + ";", true);
        resolve.visitVarInsn(ASTORE, 2);
        resolve.visitInsn(ICONST_0);
        resolve.visitVarInsn(ISTORE, 3);
        Label loop = new Label();
        Label done = new Label();
        resolve.visitLabel(loop);
        resolve.visitVarInsn(ILOAD, 3);
        resolve.visitVarInsn(ALOAD, 1);
        resolve.visitInsn(ARRAYLENGTH);
        resolve.visitJumpInsn(IF_ICMPGE, done);
        resolve.visitVarInsn(ALOAD, 2);
        resolve.visitVarInsn(ALOAD, 1);
        resolve.visitVarInsn(ILOAD, 3);
        resolve.visitInsn(AALOAD);
        resolve.visitMethodInsn(INVOKEINTERFACE, PATH, "resolve", "(Ljava/lang/String;)L" + PATH + ";", true);
        resolve.visitVarInsn(ASTORE, 2);
        resolve.visitIincInsn(3, 1);
        resolve.visitJumpInsn(GOTO, loop);
        resolve.visitLabel(done);
        resolve.visitVarInsn(ALOAD, 2);
        resolve.visitInsn(ARETURN);
        resolve.visitMaxs(0, 0);
        resolve.visitEnd();

        // public Path getSource() { return super.getSource(); }
        MethodVisitor source = cw.visitMethod(ACC_PUBLIC, "getSource", GET_SOURCE, null, null);
        source.visitCode();
        source.visitVarInsn(ALOAD, 0);
        source.visitMethodInsn(INVOKESPECIAL, FORGE_PACK, "getSource", GET_SOURCE, false);
        source.visitInsn(ARETURN);
        source.visitMaxs(0, 0);
        source.visitEnd();

        // public IoSupplier getResource(PackType type, Identifier id) { return super.getResource(type, id); }
        MethodVisitor resource = cw.visitMethod(ACC_PUBLIC, "getResource", GET_RESOURCE, null, null);
        resource.visitCode();
        resource.visitVarInsn(ALOAD, 0);
        resource.visitVarInsn(ALOAD, 1);
        resource.visitVarInsn(ALOAD, 2);
        resource.visitMethodInsn(INVOKESPECIAL, FORGE_PACK, "getResource", GET_RESOURCE, false);
        resource.visitInsn(ARETURN);
        resource.visitMaxs(0, 0);
        resource.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Like DynamicTrees' pack: the old constructor, and a call to the inherited {@code resolve}. */
    static byte[] flatPack() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/FlatPack", null, FORGE_PACK, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", OLD_CTOR, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ILOAD, 2);
        init.visitVarInsn(ALOAD, 3);
        init.visitMethodInsn(INVOKESPECIAL, FORGE_PACK, "<init>", OLD_CTOR, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor data = cw.visitMethod(ACC_PUBLIC, "dataFolder", GET_SOURCE, null, null);
        data.visitCode();
        data.visitVarInsn(ALOAD, 0);
        data.visitInsn(ICONST_1);
        data.visitTypeInsn(ANEWARRAY, "java/lang/String");
        data.visitInsn(DUP);
        data.visitInsn(ICONST_0);
        data.visitLdcInsn("data");
        data.visitInsn(AASTORE);
        data.visitMethodInsn(INVOKEVIRTUAL, "mod/FlatPack", "resolve", RESOLVE, false);
        data.visitInsn(ARETURN);
        data.visitMaxs(0, 0);
        data.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Like Moonlight's DynamicPackCache: a subclass of the vanilla pack with vanilla 1.20.1's constructor. */
    static byte[] vanillaPack() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/CachePack", null, VANILLA_PACK, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", VANILLA_1201_CTOR, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitVarInsn(ILOAD, 3);
        init.visitMethodInsn(INVOKESPECIAL, VANILLA_PACK, "<init>", VANILLA_1201_CTOR, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Like Moonlight, CreativeCore and Embeddium: a new pack, an instanceof with getSource, and a cast with a read. */
    static byte[] builder() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Builder", null, "java/lang/Object", null);
        MethodVisitor build = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "build", "(Ljava/lang/String;L" + PATH + ";)Ljava/lang/Object;", null, null);
        build.visitCode();
        build.visitTypeInsn(NEW, FORGE_PACK);
        build.visitInsn(DUP);
        build.visitVarInsn(ALOAD, 0);
        build.visitInsn(ICONST_1);
        build.visitVarInsn(ALOAD, 1);
        build.visitMethodInsn(INVOKESPECIAL, FORGE_PACK, "<init>", OLD_CTOR, false);
        build.visitInsn(ARETURN);
        build.visitMaxs(0, 0);
        build.visitEnd();

        MethodVisitor sourceOf = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "sourceOf", "(Ljava/lang/Object;)L" + PATH + ";", null, null);
        sourceOf.visitCode();
        Label other = new Label();
        sourceOf.visitVarInsn(ALOAD, 0);
        sourceOf.visitTypeInsn(INSTANCEOF, FORGE_PACK);
        sourceOf.visitJumpInsn(IFEQ, other);
        sourceOf.visitVarInsn(ALOAD, 0);
        sourceOf.visitTypeInsn(CHECKCAST, FORGE_PACK);
        sourceOf.visitMethodInsn(INVOKEVIRTUAL, FORGE_PACK, "getSource", GET_SOURCE, false);
        sourceOf.visitInsn(ARETURN);
        sourceOf.visitLabel(other);
        sourceOf.visitFrame(F_SAME, 0, null, 0, null);
        sourceOf.visitInsn(ACONST_NULL);
        sourceOf.visitInsn(ARETURN);
        sourceOf.visitMaxs(0, 0);
        sourceOf.visitEnd();

        MethodVisitor meta = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "rootMcmeta", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        meta.visitCode();
        meta.visitVarInsn(ALOAD, 0);
        meta.visitTypeInsn(CHECKCAST, FORGE_PACK);
        meta.visitInsn(ICONST_1);
        meta.visitTypeInsn(ANEWARRAY, "java/lang/String");
        meta.visitInsn(DUP);
        meta.visitInsn(ICONST_0);
        meta.visitLdcInsn("pack.mcmeta");
        meta.visitInsn(AASTORE);
        meta.visitMethodInsn(INVOKEVIRTUAL, FORGE_PACK, "getRootResource", ROOT_RESOURCE, false);
        meta.visitInsn(ARETURN);
        meta.visitMaxs(0, 0);
        meta.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code @Mixin(PathPackResources.class) abstract class ... {}} on Forge's class, under the given name. */
    static byte[] forgePackMixin(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER, name, null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation(MIXIN, false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(FORGE_PACK));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Small stand-ins for the vanilla pack classes Forge 26.2 and 26.3 ship. */
    private static ForgeHostStandIns hostStandIns() {
        return new ForgeHostStandIns()
                .add("net/minecraft/network/chat/Component.java", """
                        package net.minecraft.network.chat;
                        public interface Component {
                            String getString();
                            static MutableComponent literal(String text) { return new MutableComponent(text); }
                        }
                        """)
                .add("net/minecraft/network/chat/MutableComponent.java", """
                        package net.minecraft.network.chat;
                        public final class MutableComponent implements Component {
                            private final String text;
                            MutableComponent(String text) { this.text = text; }
                            public String getString() { return text; }
                        }
                        """)
                .add("net/minecraft/server/packs/repository/PackSource.java", """
                        package net.minecraft.server.packs.repository;
                        public interface PackSource {
                            PackSource DEFAULT = new PackSource() {};
                            PackSource BUILT_IN = new PackSource() {};
                        }
                        """)
                .add("net/minecraft/server/packs/PackLocationInfo.java", """
                        package net.minecraft.server.packs;
                        public record PackLocationInfo(String id, net.minecraft.network.chat.Component title,
                                net.minecraft.server.packs.repository.PackSource source, java.util.Optional<?> knownPackInfo) {}
                        """)
                .add("net/minecraft/resources/Identifier.java", """
                        package net.minecraft.resources;
                        public final class Identifier {
                            private final String namespace;
                            private final String path;
                            private Identifier(String namespace, String path) { this.namespace = namespace; this.path = path; }
                            public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
                            public String getNamespace() { return namespace; }
                            public String getPath() { return path; }
                            @Override public String toString() { return namespace + ":" + path; }
                        }
                        """)
                .add("net/minecraft/server/packs/PackType.java", """
                        package net.minecraft.server.packs;
                        public enum PackType {
                            CLIENT_RESOURCES("assets"), SERVER_DATA("data");
                            private final String directory;
                            PackType(String directory) { this.directory = directory; }
                            public String getDirectory() { return directory; }
                        }
                        """)
                .add("net/minecraft/server/packs/resources/IoSupplier.java", """
                        package net.minecraft.server.packs.resources;
                        public interface IoSupplier<T> {
                            T get() throws java.io.IOException;
                            static IoSupplier<java.io.InputStream> create(java.nio.file.Path path) {
                                return () -> java.nio.file.Files.newInputStream(path);
                            }
                        }
                        """)
                .add("net/minecraft/server/packs/PackResources.java", """
                        package net.minecraft.server.packs;
                        import java.io.InputStream;
                        import net.minecraft.resources.Identifier;
                        import net.minecraft.server.packs.resources.IoSupplier;
                        public interface PackResources {
                            interface ResourceOutput extends java.util.function.BiConsumer<Identifier, IoSupplier<InputStream>> {}
                            IoSupplier<InputStream> getRootResource(String... paths);
                            IoSupplier<InputStream> getResource(PackType type, Identifier id);
                            void listResources(PackType type, String namespace, String path, ResourceOutput output);
                            java.util.Set<String> getNamespaces(PackType type);
                            PackLocationInfo location();
                        }
                        """)
                .add("net/minecraft/server/packs/PathPackResources.java", """
                        package net.minecraft.server.packs;
                        import java.io.IOException;
                        import java.io.InputStream;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.util.Set;
                        import java.util.TreeSet;
                        import net.minecraft.resources.Identifier;
                        import net.minecraft.server.packs.resources.IoSupplier;
                        public class PathPackResources implements PackResources {
                            private final PackLocationInfo location;
                            private final Path root;
                            public PathPackResources(PackLocationInfo location, Path root) { this.location = location; this.root = root; }
                            public PackLocationInfo location() { return location; }
                            public IoSupplier<InputStream> getRootResource(String... paths) {
                                Path file = root;
                                for (String segment : paths) file = file.resolve(segment);
                                return Files.exists(file) ? IoSupplier.create(file) : null;
                            }
                            public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
                                return getRootResource(type.getDirectory(), id.getNamespace(), id.getPath());
                            }
                            public void listResources(PackType type, String namespace, String path, PackResources.ResourceOutput output) {
                                listResources(root.resolve(type.getDirectory()), namespace, path, output);
                            }
                            public static void listResources(Path packDir, String namespace, String path, PackResources.ResourceOutput output) {
                                Path base = packDir.resolve(namespace);
                                Path start = path.isEmpty() ? base : base.resolve(path);
                                if (!Files.isDirectory(start)) return;
                                try (var files = Files.walk(start)) {
                                    files.filter(Files::isRegularFile).forEach(file -> output.accept(
                                            Identifier.fromNamespaceAndPath(namespace, base.relativize(file).toString().replace('\\\\', '/')),
                                            IoSupplier.create(file)));
                                } catch (IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            }
                            public Set<String> getNamespaces(PackType type) { return getNamespaces(root.resolve(type.getDirectory())); }
                            public static Set<String> getNamespaces(Path packDir) {
                                Set<String> found = new TreeSet<>();
                                if (!Files.isDirectory(packDir)) return found;
                                try (var entries = Files.list(packDir)) {
                                    entries.filter(Files::isDirectory).forEach(entry -> found.add(entry.getFileName().toString()));
                                } catch (IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                                return found;
                            }
                        }
                        """);
    }

    /** Reflection over the stand-in host classes the translated code runs against. */
    private static final class Host {
        final ClassLoader loader;
        final Class<?> pathPack;

        Host(ClassLoader loader) throws ClassNotFoundException {
            this.loader = loader;
            this.pathPack = loader.loadClass("net.minecraft.server.packs.PathPackResources");
        }

        Object call(Object target, String name) throws Exception {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        }

        Object packSource(String name) throws Exception {
            return loader.loadClass("net.minecraft.server.packs.repository.PackSource").getField(name).get(null);
        }

        Object packType(String name) throws Exception {
            return loader.loadClass("net.minecraft.server.packs.PackType").getField(name).get(null);
        }

        Object identifier(String namespace, String path) throws Exception {
            return loader.loadClass("net.minecraft.resources.Identifier")
                    .getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, namespace, path);
        }

        Object newVanillaPack(String id, Path root) throws Exception {
            Class<?> component = loader.loadClass("net.minecraft.network.chat.Component");
            Class<?> source = loader.loadClass("net.minecraft.server.packs.repository.PackSource");
            Class<?> info = loader.loadClass("net.minecraft.server.packs.PackLocationInfo");
            Object title = component.getMethod("literal", String.class).invoke(null, id);
            Object location = info.getConstructor(String.class, component, source, Optional.class)
                    .newInstance(id, title, packSource("DEFAULT"), Optional.empty());
            return pathPack.getConstructor(info, Path.class).newInstance(location, root);
        }

        Object rootResource(Object pack, String... paths) throws Exception {
            return pathPack.getMethod("getRootResource", String[].class).invoke(pack, (Object) paths);
        }

        Object resource(Object pack, String type, String namespace, String path) throws Exception {
            Class<?> packType = loader.loadClass("net.minecraft.server.packs.PackType");
            Class<?> identifier = loader.loadClass("net.minecraft.resources.Identifier");
            return pathPack.getMethod("getResource", packType, identifier)
                    .invoke(pack, packType(type), identifier(namespace, path));
        }

        @SuppressWarnings("unchecked")
        Set<String> namespaces(Object pack, String type) throws Exception {
            Class<?> packType = loader.loadClass("net.minecraft.server.packs.PackType");
            return new TreeSet<>((Set<String>) pathPack.getMethod("getNamespaces", packType).invoke(pack, packType(type)));
        }

        Set<String> list(Object pack, String type, String namespace) throws Exception {
            Class<?> packType = loader.loadClass("net.minecraft.server.packs.PackType");
            Class<?> output = loader.loadClass("net.minecraft.server.packs.PackResources$ResourceOutput");
            Set<String> found = new TreeSet<>();
            Object collector = Proxy.newProxyInstance(loader, new Class<?>[]{output}, (proxy, method, args) -> {
                if (method.getName().equals("accept")) found.add(String.valueOf(args[0]));
                return null;
            });
            pathPack.getMethod("listResources", packType, String.class, String.class, output)
                    .invoke(pack, packType(type), namespace, "", collector);
            return found;
        }

        String read(Object supplier) throws Exception {
            assertNotNull(supplier, "the pack found no file");
            Method get = loader.loadClass("net.minecraft.server.packs.resources.IoSupplier").getMethod("get");
            try (InputStream in = (InputStream) get.invoke(supplier)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private static List<String> mixinTargets(ClassNode node) {
        List<String> found = new ArrayList<>();
        for (List<AnnotationNode> annotations : java.util.Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (!MIXIN.equals(annotation.desc) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    found.add(annotation.values.get(i) + "=" + annotation.values.get(i + 1));
                }
            }
        }
        return found;
    }

    private static List<String> calls(MethodNode method, String owner) {
        List<String> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) {
                found.add(opcode(call.getOpcode()) + " " + call.owner + "." + call.name + call.desc);
            }
        }
        return found;
    }

    private static List<String> typeInsns(MethodNode method) {
        List<String> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type && (type.getOpcode() == INSTANCEOF || type.getOpcode() == CHECKCAST)) {
                found.add((type.getOpcode() == INSTANCEOF ? "INSTANCEOF " : "CHECKCAST ") + type.desc);
            }
        }
        return found;
    }

    private static String opcode(int opcode) {
        return switch (opcode) {
            case INVOKESTATIC -> "INVOKESTATIC";
            case INVOKESPECIAL -> "INVOKESPECIAL";
            case INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            default -> "INVOKEINTERFACE";
        };
    }

    private static void resetBlocklist() throws Exception {
        Method reset = MixinBlocklist.class.getDeclaredMethod("resetForTesting");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name));
    }

    private static byte[] read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        return jar.getInputStream(entry).readAllBytes();
    }

    private static Path onlyJar(Path dir) throws Exception {
        try (var jars = Files.list(dir)) {
            List<Path> all = jars.filter(path -> path.toString().endsWith(".jar")).toList();
            assertEquals(1, all.size(), "the batch wrote one jar: " + all);
            return all.get(0);
        }
    }

    private static byte[] toml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
