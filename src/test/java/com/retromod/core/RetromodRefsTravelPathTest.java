/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.forge.ForgeNeoForgeSynthetics;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

/** Every Retromod class a translated Forge 1.20.1 recipe serializer mod names travels in its jar or ships in Retromod. */
class RetromodRefsTravelPathTest {

    private static final String SERIALIZERS = "regmod/Serializers";
    private static final String SUPPLIER = "regmod/ThingSupplier";
    private static final String SERIALIZER = "regmod/ThingSerializer";
    private static final String CRATE = "regmod/Crate";
    private static final String DEFERRED = "net/minecraftforge/registries/DeferredRegister";
    private static final String RECIPE_SERIALIZER = "net/minecraft/world/item/crafting/RecipeSerializer";
    private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    private static final String COMPOUND = "Lnet/minecraft/nbt/CompoundTag;";
    private static final String ADAPTER = "com/retromod/shim/common/embedded/LegacyRecipeSerializerBridge";

    @TempDir
    Path dir;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private final boolean savedForge = McReflect.isForceForge();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        McReflect.setForceForge(savedForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{1} on {0} {2}")
    @CsvSource({"neoforge, runtime, 1.21.11", "neoforge, transform, 1.21.11", "neoforge, batch, 1.21.11",
            "neoforge, aot, 1.21.11", "neoforge, runtime, 26.3", "neoforge, batch, 26.3",
            "forge, runtime, 1.21.11", "forge, batch, 1.21.11", "forge, batch, 26.3"})
    @DisplayName("A registered recipe serializer and an old block entity name only classes the jar or Retromod has")
    void everyRetromodReferenceResolves(String loader, String path, String host) throws Exception {
        targetLoader(loader);
        Path output = translate(loader, path, host);
        Map<String, byte[]> classes = classes(output);

        // The registry id bridge runs the mod's serializer supplier, so the case is the one the survey found.
        String bridge = "neoforge".equals(loader) ? "RegistryIdBridge" : "ForgeRegistryIdBridge";
        assertTrue(hasEmbedded(classes, "com/retromod/shim/forge/embedded/" + bridge),
                path + " must embed the registry id bridge: " + classes.keySet());
        // From 26.1 that bridge adapts an old serializer, so the adapter travels with it; before, nothing names it.
        boolean adapts = RetromodVersion.compareMcVersions(host, "26.1") >= 0;
        assertEquals(adapts, hasEmbedded(classes, ADAPTER), path + " on " + host + ": " + classes.keySet());
        // The block entity's old override goes through the registry NBT helper, whose world data lookup is host-gated too.
        assertTrue(hasEmbedded(classes, "com/retromod/generated/LegacyRegistryNbt"), path + ": " + classes.keySet());

        Map<String, Set<String>> missing = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            for (String ref : SyntheticEmbedder.referencedClasses(entry.getValue())) {
                if (ref.startsWith("com/retromod/") && !classes.containsKey(ref) && !shipsInRetromod(ref)) {
                    missing.computeIfAbsent(ref, k -> new TreeSet<>()).add(entry.getKey());
                }
            }
        }
        assertTrue(missing.isEmpty(), path + " on " + loader + " " + host
                + " names Retromod classes that neither the jar nor Retromod has (class <- named by): " + missing);
    }

    /** Sets the loader the game or {@code --target-loader} would set before any shim registers. */
    private static void targetLoader(String loader) {
        McReflect.setForceNeoForge("neoforge".equals(loader));
        McReflect.setForceForge("forge".equals(loader));
        ModVersionDetector.setDefaultPreferredLoader(loader);
    }

    /** Translates the fixture for {@code host} through one path and returns the output jar. */
    private Path translate(String loader, String path, String host) throws Exception {
        Path input = writeMod();
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> {
                registerEntryPoint(loader, host);
                yield new ForgeModTransformer(host).transformMod(input, out);
            }
            case "transform" -> {
                CliHarness.transform(input, out.resolve("regmod.jar"), host);
                yield out.resolve("regmod.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("regmod.jar"), host);
                yield out.resolve("regmod.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                yield out.resolve(input.getFileName());
            }
        };
        assertTrue(output != null && Files.isRegularFile(output), path + " must produce a jar");
        return output;
    }

    /** What RetromodNeoForge or RetromodForge registers on {@code host} before it translates a mod. */
    private void registerEntryPoint(String loader, String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Set<String> loaders = "neoforge".equals(loader) ? Set.of("neoforge", "forge", "common") : Set.of("forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (loaders.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        if ("neoforge".equals(loader)) ForgeNeoForgeSynthetics.register(transformer);
    }

    private Path writeMod() throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("regmod-1.20.1.jar");
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"regmod\"\nversion=\"1.0.0\"\n"
                + "[[dependencies.regmod]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[47,)\"\n"
                + "[[dependencies.regmod]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.20.1,1.21)\"\n";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (Map.Entry<String, byte[]> type : Map.of(SERIALIZERS, serializers(), SUPPLIER, supplier(),
                    SERIALIZER, serializer(), CRATE, crate()).entrySet()) {
                out.putNextEntry(new JarEntry(type.getKey() + ".class"));
                out.write(type.getValue());
                out.closeEntry();
            }
        }
        return input;
    }

    /** A deferred register for recipe serializers, and {@code register("thing", new ThingSupplier())} on it. */
    private static byte[] serializers() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, SERIALIZERS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, "net/minecraftforge/registries/ForgeRegistries$Keys", "RECIPE_SERIALIZERS",
                "Lnet/minecraft/resources/ResourceKey;");
        mv.visitLdcInsn("regmod");
        mv.visitMethodInsn(INVOKESTATIC, DEFERRED, "create",
                "(Lnet/minecraft/resources/ResourceKey;Ljava/lang/String;)L" + DEFERRED + ";", false);
        mv.visitLdcInsn("thing");
        mv.visitTypeInsn(NEW, SUPPLIER);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, SUPPLIER, "<init>", "()V", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, DEFERRED, "register",
                "(Ljava/lang/String;Ljava/util/function/Supplier;)Lnet/minecraftforge/registries/RegistryObject;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The supplier the register call runs: {@code () -> new ThingSerializer()}. */
    private static byte[] supplier() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, SUPPLIER, null, "java/lang/Object", new String[]{"java/util/function/Supplier"});
        constructor(cw, "java/lang/Object");
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
        get.visitCode();
        get.visitTypeInsn(NEW, SERIALIZER);
        get.visitInsn(DUP);
        get.visitMethodInsn(INVOKESPECIAL, SERIALIZER, "<init>", "()V", false);
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 1.20.1 serializer: a class that implements {@code RecipeSerializer}. */
    private static byte[] serializer() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, SERIALIZER, null, "java/lang/Object", new String[]{RECIPE_SERIALIZER});
        constructor(cw, "java/lang/Object");
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A block entity with the old {@code getUpdateTag()} override, which the registry NBT helper bridges. */
    private static byte[] crate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, CRATE, null, BLOCK_ENTITY, null);
        MethodVisitor tag = cw.visitMethod(ACC_PUBLIC, "getUpdateTag", "()" + COMPOUND, null, null);
        tag.visitCode();
        tag.visitVarInsn(ALOAD, 0);
        tag.visitMethodInsn(INVOKEVIRTUAL, CRATE, "saveWithoutMetadata", "()" + COMPOUND, false);
        tag.visitInsn(ARETURN);
        tag.visitMaxs(0, 0);
        tag.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(ClassWriter cw, String parent) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, parent, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    /** Every class in the jar, by internal name. */
    private static Map<String, byte[]> classes(Path jar) throws Exception {
        Map<String, byte[]> out = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                if (entry.getName().endsWith(".class")) {
                    out.put(entry.getName().substring(0, entry.getName().length() - 6),
                            file.getInputStream(entry).readAllBytes());
                }
            }
        }
        return out;
    }

    /** Whether the jar holds its own copy of {@code name} under the per-mod embedded package. */
    private static boolean hasEmbedded(Map<String, byte[]> classes, String name) {
        return classes.keySet().stream().anyMatch(c -> c.startsWith(SyntheticEmbedder.PREFIX) && c.endsWith("/" + name));
    }

    /** Whether Retromod's own build has the class, as a game with Retromod installed does; test classes do not count. */
    private static boolean shipsInRetromod(String name) {
        java.net.URL url = RetromodTransformer.class.getClassLoader().getResource(name + ".class");
        return url != null && !url.getPath().contains("/test-classes/");
    }
}
