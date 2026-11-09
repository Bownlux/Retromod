/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.mapping.IntermediaryToMojangMapper;
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
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Every class-transform path moves a GeckoLib 4 mod to com.geckolib only where GeckoLib uses it, never GeckoLib 4 or 5. */
class GeckoLibMovesPathParityTest {

    private static final String MOD_CLASS = "mod/GeoThing";
    private static final String OLD = "software/bernie/geckolib/";
    private static final String GEO_ITEM = OLD + "animatable/GeoItem";
    private static final String UTIL = OLD + "util/GeckoLibUtil";
    private static final String CORE_ANIMATABLE = OLD + "core/animatable/GeoAnimatable";
    private static final String CORE_CACHE = OLD + "core/animatable/instance/AnimatableInstanceCache";
    /** The classes of the GeckoLib in miniature: its main class, and the classes the mod names. */
    private static final List<String> GECKOLIB_CLASSES =
            List.of(OLD + "GeckoLib", GEO_ITEM, UTIL, CORE_ANIMATABLE, CORE_CACHE);

    /** The classes a GeckoLib 4 mod names once moved: the table's destinations. */
    private static final Set<String> MOVED = Set.of("com/geckolib/animatable/GeoItem", "com/geckolib/util/GeckoLibUtil",
            "com/geckolib/animatable/GeoAnimatable", "com/geckolib/animatable/instance/AnimatableInstanceCache");
    /** What it names when nothing moves, its reflection string included. */
    private static final Set<String> KEPT = Set.of(GEO_ITEM, UTIL, CORE_ANIMATABLE, CORE_CACHE,
            "\"software.bernie.geckolib.animatable.GeoItem\"");

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
        transformer.clearLinkedLibraries();
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} {1} on {2}: {3}")
    @CsvSource({
            "fabric, runtime, 1.21.11, kept", "fabric, runtime, 26.3, moved", "fabric, transform, 1.21.11, kept",
            "fabric, transform, 26.3, moved", "fabric, batch, 26.3, moved", "fabric, aot, 26.3, moved",
            "forge, runtime, 1.21.11, kept", "forge, runtime, 26.3, moved", "forge, transform, 26.3, moved",
            "forge, batch, 1.21.11, kept", "forge, aot, 26.2, moved",
            "neoforge, runtime, 1.21.11, kept", "neoforge, runtime, 26.1, moved", "neoforge, runtime, 26.3, moved",
            "neoforge, transform, 1.21.11, kept", "neoforge, batch, 26.3, moved", "neoforge, aot, 1.21.11, kept",
            "neoforge, aot, 26.3, moved"})
    @DisplayName("A GeckoLib 4 mod (Minecraft 1.20.1) moves only where the host's GeckoLib uses com.geckolib")
    void geckoLib4ModMovesOnlyWhereGeckoLibDid(String loader, String path, String host, String expected)
            throws Exception {
        Path input = write(dir.resolve("in"), "geothing.jar", modJar(loader, "geothing", "1.20.1"));

        Set<String> names = geckoLibNames(translate(loader, path, host, input));

        if (expected.equals("moved")) {
            // Class references are what moves; no path rewrites this reflection string today.
            names.removeIf(name -> name.startsWith("\""));
            assertEquals(MOVED, names, loader + " " + path + " on " + host);
        } else {
            assertEquals(KEPT, names, loader + " " + path + " on " + host);
        }
    }

    @ParameterizedTest(name = "{0} {1} on {2}")
    @CsvSource({"fabric, runtime, 26.3", "fabric, transform, 26.3", "neoforge, runtime, 26.3",
            "neoforge, batch, 26.3", "neoforge, aot, 26.3", "forge, transform, 26.2"})
    @DisplayName("A GeckoLib 5 mod (Minecraft 1.21.11) keeps its software.bernie names on every path")
    void geckoLib5ModIsNeverMoved(String loader, String path, String host) throws Exception {
        Path input = write(dir.resolve("in"), "geothing.jar", modJar(loader, "geothing", "1.21.11"));

        assertEquals(KEPT, geckoLibNames(translate(loader, path, host, input)), loader + " " + path + " on " + host);
    }

    @ParameterizedTest(name = "batch {0} with the GeckoLib for {1}")
    @CsvSource({"transform, 1.21.11", "aot, 1.21.11", "transform, 1.20.1", "aot, 1.20.1"})
    @DisplayName("A batch that carries a GeckoLib with software.bernie keeps a GeckoLib 4 mod's names on 26.3")
    void batchReadsTheGeckoLibItCarries(String mode, String geckoLibSource) throws Exception {
        Path in = dir.resolve("in");
        write(in, "geothing.jar", modJar("neoforge", "geothing", "1.20.1"));
        write(in, "geckolib.jar", geckoLibJar("neoforge", geckoLibSource));
        McReflect.setForceNeoForge(true);
        ModVersionDetector.setDefaultPreferredLoader("neoforge");
        Path out = Files.createDirectories(dir.resolve("out"));

        if (mode.equals("aot")) {
            CliHarness.batch(in, out, "26.3", "--aot");
        } else {
            CliHarness.batch(in, out, "26.3");
        }

        assertEquals(KEPT, geckoLibNames(output(out, "geothing")),
                "the batch's GeckoLib still answers to software.bernie");
        assertKeepsItsOwnNames(output(out, "geckolib"));
    }

    @ParameterizedTest(name = "{0} {1} on {2}")
    @CsvSource({"fabric, runtime, 26.3", "fabric, transform, 26.3", "fabric, batch, 26.3",
            "forge, runtime, 26.2", "forge, transform, 26.2", "forge, aot, 26.2",
            "neoforge, runtime, 26.3", "neoforge, batch, 26.3", "neoforge, aot, 26.3"})
    @DisplayName("GeckoLib 4 translated with no other GeckoLib in sight keeps its own software.bernie names")
    void geckoLib4KeepsItsOwnNames(String loader, String path, String host) throws Exception {
        Path input = write(dir.resolve("in"), "geckolib.jar", geckoLibJar(loader, "1.20.1"));

        assertKeepsItsOwnNames(translate(loader, path, host, input));
    }

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({"fabric, 26.3", "forge, 26.2", "neoforge, 26.3"})
    @DisplayName("A running game that translates GeckoLib 4 and its mod together keeps both on software.bernie")
    void runtimeLinksTheModToTheGeckoLib4BesideIt(String loader, String host) throws Exception {
        Path in = dir.resolve("in");
        Path geckoLib = write(in, "geckolib.jar", geckoLibJar(loader, "1.20.1"));
        Path mod = write(in, "geothing.jar", modJar(loader, "geothing", "1.20.1"));
        selectLoader(loader);
        Path out = Files.createDirectories(dir.resolve("out"));
        registerRuntime(loader, host);
        SyntheticLibraries libraries = SyntheticLibraries.create();

        // The entry point's order: the library first, then the mod linked with its translation.
        Path translatedGeckoLib = runtimeTransform(loader, host, geckoLib, out, libraries);
        libraries.add(translatedGeckoLib);
        Path translatedMod = runtimeTransform(loader, host, mod, out, libraries);

        assertKeepsItsOwnNames(translatedGeckoLib);
        assertEquals(KEPT, geckoLibNames(translatedMod), "the mod names the GeckoLib 4 that loads beside it");
    }

    /** Translates {@code input} for a {@code loader} host through one path and returns the output jar. */
    private Path translate(String loader, String path, String host, Path input) throws Exception {
        selectLoader(loader);
        Path out = Files.createDirectories(dir.resolve("out"));
        Path named = out.resolve(input.getFileName());
        Path output = switch (path) {
            case "runtime" -> runtime(loader, host, input, out);
            case "transform" -> {
                CliHarness.transform(input, named, host);
                yield named;
            }
            case "aot" -> {
                CliHarness.aot(input, named, host);
                yield named;
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

    private static void selectLoader(String loader) {
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader.equals("fabric") ? null : loader);
    }

    /** Registers what the loader's entry point registers on {@code host}, then runs its mod transformer. */
    private Path runtime(String loader, String host, Path input, Path out) throws Exception {
        registerRuntime(loader, host);
        return runtimeTransform(loader, host, input, out, SyntheticLibraries.NONE);
    }

    /** Registers what the loader's entry point registers on {@code host}, where no GeckoLib can be read. */
    private void registerRuntime(String loader, String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        if (loader.equals("fabric")) {
            RetromodPreLaunch.registerShimsForTransform(host);
            return;
        }
        Set<String> types = loader.equals("forge") ? Set.of("forge", "common") : Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
    }

    /** The loader's mod transformer on one jar, linked with {@code libraries} as the entry points link it. */
    private static Path runtimeTransform(String loader, String host, Path input, Path out,
            SyntheticLibraries libraries) throws Exception {
        return loader.equals("fabric")
                ? new FabricModTransformer(host).transformMod(input, out, libraries)
                : new ForgeModTransformer(host).transformMod(input, out, libraries);
    }

    /** The jar a batch wrote for the input whose name starts with {@code prefix}. */
    private static Path output(Path out, String prefix) throws Exception {
        try (var jars = Files.list(out)) {
            return jars.filter(jar -> jar.getFileName().toString().startsWith(prefix)).findFirst().orElseThrow();
        }
    }

    /** Every GeckoLib class file is still named for its entry path, and nothing in the jar names com.geckolib. */
    private static void assertKeepsItsOwnNames(Path jar) throws Exception {
        int own = 0;
        try (JarFile file = new JarFile(jar.toFile())) {
            for (var entries = file.entries(); entries.hasMoreElements(); ) {
                String entry = entries.nextElement().getName();
                if (!entry.endsWith(".class") || entry.startsWith("META-INF/")) continue;
                ClassNode node = new ClassNode();
                new ClassReader(file.getInputStream(file.getJarEntry(entry)).readAllBytes()).accept(node, 0);
                String expected = entry.substring(0, entry.length() - ".class".length());
                assertEquals(expected, node.name, "a class moved away from its own entry path in " + jar.getFileName());
                if (expected.startsWith(OLD)) own++;
                Set<String> names = new TreeSet<>();
                node.accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
                    @Override
                    public String map(String internalName) {
                        names.add(internalName);
                        return internalName;
                    }
                }));
                names.removeIf(name -> !name.startsWith("com/geckolib/"));
                assertEquals(Set.of(), names, expected + " names com.geckolib");
            }
        }
        assertEquals(GECKOLIB_CLASSES.size(), own, "every GeckoLib class is still in " + jar.getFileName());
    }

    /** Every GeckoLib class the mod class names, and its GeckoLib reflection string in quotes. */
    private static Set<String> geckoLibNames(Path jar) throws Exception {
        ClassNode node = new ClassNode();
        try (JarFile file = new JarFile(jar.toFile())) {
            new ClassReader(file.getInputStream(file.getJarEntry(MOD_CLASS + ".class")).readAllBytes()).accept(node, 0);
        }
        Set<String> names = new TreeSet<>(node.interfaces);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    names.add(call.owner);
                    for (Type type : Type.getArgumentTypes(call.desc)) names.add(type.getInternalName());
                    names.add(Type.getReturnType(call.desc).getInternalName());
                } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                    names.add('"' + text + '"');
                }
            }
        }
        names.removeIf(name -> !name.contains("geckolib"));
        return names;
    }

    /** A {@code loader} mod for {@code source} with one GeckoLib item class. */
    private static byte[] modJar(String loader, String modId, String source) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(metadataPath(loader), metadata(loader, modId, source));
        entries.put(MOD_CLASS + ".class", geoThing());
        return LoaderApiFixtures.jar(entries);
    }

    /**
     * A GeckoLib in miniature for {@code source}: 4.4.9 for 1.20.1, or 5.4.5 for 1.21.11, both on the old
     * package. Its util class builds the cache from the core classes, as the real one does.
     */
    private static byte[] geckoLibJar(String loader, String source) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(metadataPath(loader), metadata(loader, "geckolib", source));
        for (String name : GECKOLIB_CLASSES) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            boolean isInterface = name.equals(GEO_ITEM) || name.equals(CORE_ANIMATABLE);
            int access = Opcodes.ACC_PUBLIC | (isInterface ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : Opcodes.ACC_SUPER);
            writer.visit(Opcodes.V17, access, name, null, "java/lang/Object", null);
            if (name.equals(UTIL)) {
                MethodVisitor create = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "createInstanceCache", "(L" + CORE_ANIMATABLE + ";)L" + CORE_CACHE + ";", null, null);
                create.visitCode();
                create.visitInsn(Opcodes.ACONST_NULL);
                create.visitInsn(Opcodes.ARETURN);
                create.visitMaxs(0, 0);
                create.visitEnd();
            }
            writer.visitEnd();
            entries.put(name + ".class", writer.toByteArray());
        }
        return LoaderApiFixtures.jar(entries);
    }

    private static String metadataPath(String loader) {
        return switch (loader) {
            case "fabric" -> "fabric.mod.json";
            case "forge" -> "META-INF/mods.toml";
            // The reported mods are Forge 1.20.1 mods run on NeoForge.
            default -> "META-INF/mods.toml";
        };
    }

    private static byte[] metadata(String loader, String modId, String source) {
        String text = loader.equals("fabric")
                ? "{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0.0\","
                        + "\"depends\":{\"minecraft\":\"" + source + "\",\"fabricloader\":\">=0.15.0\"}}"
                : "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                        + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\n"
                        + "mandatory=true\nversionRange=\"[" + source + ",)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code GeoThing implements GeoItem}, building its cache through {@code GeckoLibUtil} and naming GeoItem reflectively. */
    private static byte[] geoThing() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CLASS, null, "java/lang/Object",
                new String[]{GEO_ITEM});
        MethodVisitor cache = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "cache",
                "(L" + CORE_ANIMATABLE + ";)Ljava/lang/Object;", null, null);
        cache.visitCode();
        cache.visitVarInsn(Opcodes.ALOAD, 0);
        cache.visitMethodInsn(Opcodes.INVOKESTATIC, UTIL, "createInstanceCache",
                "(L" + CORE_ANIMATABLE + ";)L" + CORE_CACHE + ";", false);
        cache.visitInsn(Opcodes.ARETURN);
        cache.visitMaxs(0, 0);
        cache.visitEnd();
        MethodVisitor lookup = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lookup",
                "()Ljava/lang/Class;", null, new String[]{"java/lang/ClassNotFoundException"});
        lookup.visitCode();
        lookup.visitLdcInsn("software.bernie.geckolib.animatable.GeoItem");
        lookup.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        lookup.visitInsn(Opcodes.ARETURN);
        lookup.visitMaxs(0, 0);
        lookup.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Path write(Path folder, String name, byte[] bytes) throws Exception {
        Files.createDirectories(folder);
        return Files.write(folder.resolve(name), bytes);
    }
}
