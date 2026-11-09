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
import org.objectweb.asm.tree.AnnotationNode;
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

/** Every class-transform path moves a selector and its refmap entry from resizeDisplay to resizeGui, in the mod and its nested library. */
class SelectorRenamePathParityTest {

    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String NEW_ENTRY = "L" + MINECRAFT + ";resizeGui()V";

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
    @CsvSource({"forge, runtime, 26.2", "forge, transform, 26.2", "forge, batch, 26.3", "forge, aot, 26.2",
            "neoforge, runtime, 26.3", "neoforge, transform, 26.3", "fabric, runtime, 26.3",
            "fabric, transform, 26.2", "fabric, batch, 26.3", "fabric, aot, 26.3"})
    @DisplayName("each path renames the selector and the refmap entry together")
    void everyPathFollowsTheRename(String loader, String path, String host) throws Exception {
        installHostIndex();
        Path input = Files.createDirectories(dir.resolve("in")).resolve("resize.jar");
        Files.write(input, modJar(loader));

        Path output = translate(loader, path, host, input);

        try (JarFile jar = new JarFile(output.toFile())) {
            check(jar, "mod", loader + " " + path + " on " + host);
            Path nested = Files.createTempFile(dir, "nested-", ".jar");
            Files.write(nested, jar.getInputStream(jar.getJarEntry(nestedPath(loader))).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                check(lib, "lib", loader + " " + path + " on " + host);
            }
        }
    }

    private static void check(JarFile jar, String pkg, String where) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(jar.getJarEntry(pkg + "/ResizeMixin.class")).readAllBytes()).accept(node, 0);
        MethodNode handler = node.methods.stream().filter(m -> m.name.equals("onResize")).findFirst().orElseThrow();
        assertEquals(List.of("resizeGui"), selectors(handler), pkg + " (" + where + "): the selector names the host method");
        String json = new String(jar.getInputStream(jar.getJarEntry(pkg + ".refmap.json")).readAllBytes(),
                StandardCharsets.UTF_8);
        String entry = com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("mappings")
                .getAsJsonObject(pkg + "/ResizeMixin").get("resizeDisplay").getAsString();
        assertEquals(NEW_ENTRY, entry, pkg + " refmap (" + where + "): the entry follows the selector");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> selectors(MethodNode method) {
        for (AnnotationNode annotation : method.visibleAnnotations) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (annotation.values.get(i).equals("method")) return (List<Object>) annotation.values.get(i + 1);
            }
        }
        throw new AssertionError("no selector on " + method.name);
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
                CliHarness.transform(input, out.resolve("resize.jar"), host);
                yield out.resolve("resize.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("resize.jar"), host);
                yield out.resolve("resize.jar");
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

    /** Installs a host index where Minecraft has resizeGui() and no resizeDisplay(). */
    private void installHostIndex() throws Exception {
        Path jar = Files.createTempFile(dir, "host-", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MINECRAFT, null, "java/lang/Object", null);
            MethodVisitor resize = writer.visitMethod(Opcodes.ACC_PUBLIC, "resizeGui", "()V", null, null);
            resize.visitCode();
            resize.visitInsn(Opcodes.RETURN);
            resize.visitMaxs(0, 1);
            resize.visitEnd();
            writer.visitEnd();
            out.putNextEntry(new JarEntry(MINECRAFT + ".class"));
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

    /** A mod for {@code loader} with a resize mixin and its refmap, nesting a library with the same. */
    private static byte[] modJar(String loader) throws Exception {
        boolean fabric = loader.equals("fabric");
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put(metadataPath(loader), metadata(loader, "resizelib", false));
        lib.put("lib/ResizeMixin.class", resizeMixin("lib/ResizeMixin", fabric));
        lib.put("lib.refmap.json", refmap("lib", fabric));
        if (fabric) lib.put("lib.mixins.json", mixinConfig("lib"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(metadataPath(loader), metadata(loader, "resize", true));
        mod.put("mod/ResizeMixin.class", resizeMixin("mod/ResizeMixin", fabric));
        mod.put("mod.refmap.json", refmap("mod", fabric));
        if (fabric) mod.put("mod.mixins.json", mixinConfig("mod"));
        mod.put(nestedPath(loader), LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    /** A handler at the head of the 1.21.1 resizeDisplay(); Fabric's copy names it by intermediary id. */
    private static byte[] resizeMixin(String name, boolean fabric) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(fabric ? "net/minecraft/class_310" : MINECRAFT));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, "onResize", "(" + CI + ")V", null, null);
        AnnotationVisitor inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, fabric ? "method_15993" : "resizeDisplay");
        methods.visitEnd();
        AnnotationVisitor ats = inject.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "HEAD");
        at.visitEnd();
        ats.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The refmap the mod was built with, naming the old method. */
    private static byte[] refmap(String pkg, boolean fabric) {
        String key = fabric ? "method_15993" : "resizeDisplay";
        String value = fabric ? "Lnet/minecraft/class_310;method_15993()V" : "L" + MINECRAFT + ";resizeDisplay()V";
        return ("{\"mappings\":{\"" + pkg + "/ResizeMixin\":{\"" + key + "\":\"" + value + "\"}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] mixinConfig(String pkg) {
        return ("{\"required\":true,\"package\":\"" + pkg + "\",\"refmap\":\"" + pkg + ".refmap.json\","
                + "\"mixins\":[\"ResizeMixin\"]}").getBytes(StandardCharsets.UTF_8);
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
