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
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Every class-transform path gives Balm's reload handler a layout Mixin accepts and points its refmap entry there, in the mod and its nested library. */
class InjectLayoutPathParityTest {

    private static final String RELOADABLE = "net/minecraft/server/ReloadableServerResources";
    private static final String CIR = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String EXECUTOR = "Ljava/util/concurrent/Executor;";
    /** The loadResources arguments of 26.2 and 26.3. */
    private static final String LOAD_RESOURCES_ARGS = "Lnet/minecraft/server/packs/resources/ResourceManager;"
            + "Lnet/minecraft/core/LayeredRegistryAccess;Ljava/util/List;Lnet/minecraft/world/flag/FeatureFlagSet;"
            + "Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/server/permissions/PermissionSet;"
            + EXECUTOR + EXECUTOR;
    /** The 1.20.1 loadResources arguments Balm captures, in Mojang names and in intermediary names. */
    private static final String OLD_ARGS = "Lnet/minecraft/server/packs/resources/ResourceManager;"
            + "Lnet/minecraft/core/RegistryAccess$Frozen;Lnet/minecraft/world/flag/FeatureFlagSet;"
            + "Lnet/minecraft/commands/Commands$CommandSelection;I" + EXECUTOR + EXECUTOR;
    private static final String OLD_ARGS_INTERMEDIARY = "Lnet/minecraft/class_3300;Lnet/minecraft/class_5455$class_6890;"
            + "Lnet/minecraft/class_7699;Lnet/minecraft/class_2170$class_5364;I" + EXECUTOR + EXECUTOR;
    private static final String FUTURE = "Ljava/util/concurrent/CompletableFuture;";
    /** The refmap entry the mod ships, and the one that names the 26.x method. */
    private static final String OLD_ENTRY = "L" + RELOADABLE + ";loadResources(" + OLD_ARGS + ")" + FUTURE;
    private static final String OLD_ENTRY_INTERMEDIARY = "Lnet/minecraft/class_5350;method_29466("
            + OLD_ARGS_INTERMEDIARY + ")" + FUTURE;
    private static final String NEW_ENTRY = "L" + RELOADABLE + ";loadResources(" + LOAD_RESOURCES_ARGS + ")" + FUTURE;
    /** A 1.21.11 Fabric host runs intermediary names, so its loadResources is named that way too. */
    private static final String INTERMEDIARY_RELOADABLE = "net/minecraft/class_5350";
    private static final String NEW_ARGS_INTERMEDIARY = "Lnet/minecraft/class_3300;Lnet/minecraft/class_7780;Ljava/util/List;"
            + "Lnet/minecraft/class_7699;Lnet/minecraft/class_2170$class_5364;I" + EXECUTOR + EXECUTOR;

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
    @CsvSource({"forge, runtime, 26.2", "forge, runtime, 26.3", "forge, transform, 26.2", "forge, batch, 26.3",
            "forge, aot, 26.2", "neoforge, runtime, 26.3", "neoforge, transform, 26.2", "neoforge, aot, 26.3",
            "fabric, runtime, 26.3", "fabric, transform, 26.2", "fabric, batch, 26.3", "fabric, aot, 26.3"})
    @DisplayName("each path gives a handler every 26.x argument when it reads one, the callback alone otherwise, and the refmap that method")
    void everyPathCompletesOrEmptiesTheReloadHandlers(String loader, String path, String host) throws Exception {
        installHostIndex();
        Path input = Files.createDirectories(dir.resolve("in")).resolve("balm.jar");
        Files.write(input, modJar(loader));

        Path output = translate(loader, path, host, input);

        for (Map.Entry<String, ClassNode> mixin : mixins(output, loader).entrySet()) {
            String where = mixin.getKey() + " (" + loader + " " + path + " on " + host + ")";
            assertEquals("(L" + CIR + ";)V", method(mixin.getValue(), "onReload").desc,
                    where + ": the handler reads only the callback");
            assertEquals("(" + LOAD_RESOURCES_ARGS + "L" + CIR + ";)V", method(mixin.getValue(), "onReloadManager").desc,
                    where + ": the handler reads the ResourceManager, so every argument comes with it");
        }
        for (Map.Entry<String, String> entry : refmapEntries(output, loader).entrySet()) {
            assertEquals(NEW_ENTRY, entry.getValue(), entry.getKey() + " refmap (" + loader + " " + path + " on " + host
                    + "): Mixin reads the entry, so it must name the method both handlers now fit");
        }
    }

    @ParameterizedTest(name = "fabric {0} on 1.21.11")
    @CsvSource({"runtime", "transform", "aot"})
    @DisplayName("before 26.1 a Fabric path keeps intermediary names and still points the refmap at the method the handlers fit")
    void intermediaryHostsRetargetTheRefmapToo(String path) throws Exception {
        installHostIndex(INTERMEDIARY_RELOADABLE, "method_29466", NEW_ARGS_INTERMEDIARY);
        Path input = Files.createDirectories(dir.resolve("in")).resolve("balm.jar");
        Files.write(input, modJar("fabric"));

        Path output = translate("fabric", path, "1.21.11", input);

        for (Map.Entry<String, ClassNode> mixin : mixins(output, "fabric").entrySet()) {
            String where = mixin.getKey() + " (fabric " + path + " on 1.21.11)";
            assertEquals("(L" + CIR + ";)V", method(mixin.getValue(), "onReload").desc, where);
            assertEquals("(" + NEW_ARGS_INTERMEDIARY + "L" + CIR + ";)V", method(mixin.getValue(), "onReloadManager").desc, where);
        }
        for (Map.Entry<String, String> entry : refmapEntries(output, "fabric", "method_29466").entrySet()) {
            assertEquals("L" + INTERMEDIARY_RELOADABLE + ";method_29466(" + NEW_ARGS_INTERMEDIARY + ")" + FUTURE,
                    entry.getValue(), entry.getKey() + " refmap (fabric " + path + " on 1.21.11)");
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
                CliHarness.transform(input, out.resolve("balm.jar"), host);
                yield out.resolve("balm.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("balm.jar"), host);
                yield out.resolve("balm.jar");
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

    /** Installs a host index whose loadResources has the 26.2 and 26.3 arguments. */
    private void installHostIndex() throws Exception {
        installHostIndex(RELOADABLE, "loadResources", LOAD_RESOURCES_ARGS);
    }

    /** Installs a host index whose {@code owner} declares a static {@code name} with these arguments. */
    private void installHostIndex(String owner, String name, String args) throws Exception {
        Path jar = Files.createTempFile(dir, "host-", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
            MethodVisitor load = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                    "(" + args + ")Ljava/util/concurrent/CompletableFuture;", null, null);
            load.visitCode();
            load.visitInsn(Opcodes.ACONST_NULL);
            load.visitInsn(Opcodes.ARETURN);
            load.visitMaxs(1, 8);
            load.visitEnd();
            writer.visitEnd();
            out.putNextEntry(new JarEntry(owner + ".class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }
        FuzzyMethodResolver index = new FuzzyMethodResolver();
        index.indexJar(jar);
        indexField = RetromodTransformer.class.getDeclaredField("fuzzyResolver");
        indexField.setAccessible(true);
        savedIndex = (FuzzyMethodResolver) indexField.get(transformer);
        indexField.set(transformer, index);
    }

    /** The reload mixin of the mod and of its nested library, as the output jar holds them. */
    private Map<String, ClassNode> mixins(Path output, String loader) throws Exception {
        Map<String, ClassNode> found = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            found.put("mod", read(jar, "mod/ReloadMixin"));
            Path nested = Files.createTempFile(dir, "nested-", ".jar");
            JarEntry entry = jar.getJarEntry(nestedPath(loader));
            assertNotNull(entry, "the nested library is missing from " + output.getFileName());
            Files.write(nested, jar.getInputStream(entry).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                found.put("nested", read(lib, "lib/ReloadMixin"));
            }
        }
        return found;
    }

    /** The reload entry of the mod's refmap and of its nested library's, as the output jar holds them. */
    private Map<String, String> refmapEntries(Path output, String loader) throws Exception {
        return refmapEntries(output, loader, "loadResources");
    }

    private Map<String, String> refmapEntries(Path output, String loader, String key) throws Exception {
        Map<String, String> found = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            found.put("mod", reloadEntry(jar, "mod", key));
            Path nested = Files.createTempFile(dir, "nested-", ".jar");
            Files.write(nested, jar.getInputStream(jar.getJarEntry(nestedPath(loader))).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                found.put("nested", reloadEntry(lib, "lib", key));
            }
        }
        return found;
    }

    private static String reloadEntry(JarFile jar, String pkg, String key) throws Exception {
        JarEntry entry = jar.getJarEntry(pkg + ".refmap.json");
        assertNotNull(entry, pkg + ".refmap.json is missing from " + jar.getName());
        String json = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        return com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("mappings")
                .getAsJsonObject(pkg + "/ReloadMixin").get(key).getAsString();
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

    /** A Balm-like mod for {@code loader} with the reload mixin, nesting a library with the same mixin. */
    private static byte[] modJar(String loader) throws Exception {
        boolean fabric = loader.equals("fabric");
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put(metadataPath(loader), metadata(loader, "balmlib", false));
        lib.put("lib/ReloadMixin.class", reloadMixin("lib/ReloadMixin", fabric));
        lib.put("lib.refmap.json", refmap("lib", fabric));
        if (fabric) lib.put("lib.mixins.json", mixinConfig("lib"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(metadataPath(loader), metadata(loader, "balm", true));
        mod.put("mod/ReloadMixin.class", reloadMixin("mod/ReloadMixin", fabric));
        mod.put("mod.refmap.json", refmap("mod", fabric));
        if (fabric) mod.put("mod.mixins.json", mixinConfig("mod"));
        mod.put(nestedPath(loader), LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    /**
     * Balm 1.20.1's {@code ReloadableServerResourcesMixin}: a static handler at the return of
     * {@code loadResources} that captures every old argument and reads only the callback, and a
     * second one that reads the ResourceManager. Fabric's copy names its types and target by
     * intermediary id.
     */
    private static byte[] reloadMixin(String name, boolean fabric) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(fabric ? "net/minecraft/class_5350" : RELOADABLE));
        targets.visitEnd();
        mixin.visitEnd();
        String desc = "(" + (fabric ? OLD_ARGS_INTERMEDIARY : OLD_ARGS) + "L" + CIR + ";)V";
        String selector = fabric ? "method_29466" : "loadResources";

        MethodVisitor callbackOnly = handler(cw, "onReload", desc, selector);
        callbackOnly.visitVarInsn(Opcodes.ALOAD, 7);
        callbackOnly.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CIR, "getReturnValue", "()Ljava/lang/Object;", false);
        callbackOnly.visitInsn(Opcodes.POP);
        callbackOnly.visitInsn(Opcodes.RETURN);
        callbackOnly.visitMaxs(0, 0);
        callbackOnly.visitEnd();

        MethodVisitor readsManager = handler(cw, "onReloadManager", desc, selector);
        readsManager.visitVarInsn(Opcodes.ALOAD, 0);
        readsManager.visitInsn(Opcodes.POP);
        readsManager.visitInsn(Opcodes.RETURN);
        readsManager.visitMaxs(0, 0);
        readsManager.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodVisitor handler(ClassWriter cw, String name, String desc, String selector) {
        MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, desc, null, null);
        AnnotationVisitor inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, selector);
        methods.visitEnd();
        AnnotationVisitor ats = inject.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "RETURN");
        at.visitEnd();
        ats.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        return handler;
    }

    /** The Fabric mixin config that names the package's reload mixin and refmap. */
    private static byte[] mixinConfig(String pkg) {
        return ("{\"required\":true,\"package\":\"" + pkg + "\",\"refmap\":\"" + pkg + ".refmap.json\","
                + "\"mixins\":[\"ReloadMixin\"]}").getBytes(StandardCharsets.UTF_8);
    }

    /** The refmap the mod was built with: its selector names the 1.20.1 loadResources. */
    private static byte[] refmap(String pkg, boolean fabric) {
        String key = fabric ? "method_29466" : "loadResources";
        String value = fabric ? OLD_ENTRY_INTERMEDIARY : OLD_ENTRY;
        return ("{\"mappings\":{\"" + pkg + "/ReloadMixin\":{\"" + key + "\":\"" + value + "\"}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String nestedPath(String loader) {
        return loader.equals("fabric") ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    private static String metadataPath(String loader) {
        return loader.equals("fabric") ? "fabric.mod.json" : "META-INF/mods.toml";
    }

    // Fabric and Forge mods here are built for 1.20.1, the NeoForge one for 1.20.4.
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
