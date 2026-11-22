/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** A translated mod carries the compiled Retromod helpers it calls, so it runs without Retromod installed. */
class CompiledHelperEmbeddingTest {

    private static final String ITEM_SAFETY = "com/retromod/shim/fabric/embedded/ItemSafetyShim";
    private static final String MIXIN_EXTRAS = "com/retromod/shim/api/common/embedded/MixinExtrasShim";
    private static final String MIXIN_EXTRAS_VERSION = MIXIN_EXTRAS + "$VersionCompat";
    private static final String DIRECTION_LOOKUP = "com/retromod/polyfill/registry/DirectionPropertyLookup";
    private static final String VERSION_SPOOFER = "com/retromod/compat/VersionSpoofer";
    private static final String PUBLIC_METHODS = "com/retromod/shim/common/embedded/LegacyPublicMethods";
    private static final String NETWORK_SHIM = "com/retromod/shim/forge/embedded/NetworkShim";
    private static final String PACKET_DISTRIBUTOR = NETWORK_SHIM + "$PacketDistributor";
    private static final String HOST_NAMES = "com/retromod/util/HostLibraryNames";
    private static final String SYNTHETIC_USING_HELPER = "com/retromod/generated/fixture/UsesHostNames";
    private static final String KEY_BINDING = "com/retromod/shim/fabric/embedded/KeyBindingShim";
    private static final String KEY_MAPPING = "com/retromod/polyfill/minecraft/RetroKeyMapping";
    private static final String MOD_CLASS = "example/mod/CallsHelpers";
    private static final String MOD_KEY = "example-mod.jar";

    // Top-level packages whose classes run inside a mod rather than as Retromod's runtime.
    private static final Set<String> MOD_SIDE_PACKAGES = Set.of("embedded", "generated", "legacyapi", "polyfill", "shim");

    private RetromodTransformer transformer;

    @BeforeEach
    void emptyTransformer() {
        // No registered synthetic: the helpers must travel on their own.
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void clear() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the helpers seen in standalone crashes, and the helpers they call, can travel")
    void reportedHelpersTravel() {
        for (String helper : List.of(ITEM_SAFETY, MIXIN_EXTRAS, MIXIN_EXTRAS_VERSION, DIRECTION_LOOKUP,
                VERSION_SPOOFER, PUBLIC_METHODS, "com/retromod/polyfill/registry/RegistryRefLookup",
                "com/retromod/util/HostLibraryNames", "com/retromod/shim/fabric/embedded/IdentifierShim",
                "com/retromod/util/McReflect")) {
            assertTrue(CompiledHelpers.travels(helper), helper + " should be copied into the mod that calls it");
        }
    }

    @Test
    @DisplayName("helpers that share state with the running Retromod stay in Retromod")
    void runtimeHelpersStay() {
        for (String helper : List.of(NETWORK_SHIM, PACKET_DISTRIBUTOR,
                "com/retromod/shim/forge/embedded/LegacyEventChannel",
                "com/retromod/polyfill/fabric/embedded/FabricNetworkingBridge",
                // The bridge above takes this handler type, so a mod's copy would not match it.
                "com/retromod/polyfill/fabric/embedded/ServerPlayChannelHandler",
                "com/retromod/shim/api/fabric/embedded/ClientPlayNetworkingV1Bridge",
                "com/retromod/shim/fabric/embedded/HudRenderCallbackShim",
                "com/retromod/core/RetromodTransformer",
                "com/retromod/util/RetromodLog")) {
            assertFalse(CompiledHelpers.travels(helper), helper + " must stay shared with the running Retromod");
        }
    }

    @Test
    @DisplayName("an offline jar carries every helper it calls and names only runtime-only Retromod classes")
    void offlineJarCarriesItsHelpers(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve(MOD_KEY);
        Files.write(jar, jarOf(Map.of(MOD_CLASS + ".class", modClass())));

        SyntheticEmbedder.embedIntoJar(jar, MOD_KEY, transformer);

        Map<String, byte[]> out = read(Files.readAllBytes(jar));
        String base = SyntheticEmbedder.embeddedBase(MOD_KEY);
        for (String helper : List.of(ITEM_SAFETY, MIXIN_EXTRAS, MIXIN_EXTRAS_VERSION, DIRECTION_LOOKUP,
                VERSION_SPOOFER, PUBLIC_METHODS)) {
            assertTrue(out.containsKey(base + helper + ".class"), "missing embedded copy of " + helper);
        }
        assertEquals(List.of(), missingRetromodClasses(out),
                "a class in the jar names a Retromod class it does not carry and Retromod does not keep shared");
        assertTrue(SyntheticEmbedder.referencedClasses(out.get(MOD_CLASS + ".class")).contains(PACKET_DISTRIBUTOR),
                "the runtime-only networking helper keeps its shared name");
    }

    @Test
    @DisplayName("runtime, offline, and nested-jar embedding carry the same helper copies")
    void everyPathCarriesTheSameHelpers(@TempDir Path dir) throws IOException {
        Path extracted = dir.resolve("extracted");
        Files.createDirectories(extracted.resolve("example/mod"));
        Files.write(extracted.resolve(MOD_CLASS + ".class"), modClass());
        SyntheticEmbedder.embed(extracted, MOD_KEY, transformer);
        Set<String> runtimePath = new TreeSet<>();
        try (Stream<Path> files = Files.walk(extracted)) {
            files.filter(Files::isRegularFile)
                    .forEach(f -> runtimePath.add(extracted.relativize(f).toString().replace('\\', '/')));
        }

        Path jar = dir.resolve(MOD_KEY);
        Files.write(jar, jarOf(Map.of(MOD_CLASS + ".class", modClass())));
        SyntheticEmbedder.embedIntoJar(jar, MOD_KEY, transformer);
        Set<String> offlinePath = new TreeSet<>(read(Files.readAllBytes(jar)).keySet());

        var nested = SyntheticEmbedder.embedIntoJarBytes(jarOf(Map.of(MOD_CLASS + ".class", modClass())),
                MOD_KEY, transformer);
        assertTrue(nested.succeeded());
        Set<String> nestedPath = new TreeSet<>(read(nested.jarBytes()).keySet());

        assertEquals(offlinePath, runtimePath, "the runtime path must embed what the CLI embeds");
        assertEquals(offlinePath, nestedPath, "a nested jar must embed what its parent would");
    }

    @Test
    @DisplayName("a helper that a registered synthetic calls travels with that synthetic")
    void syntheticTakesTheHelpersItCalls(@TempDir Path dir) throws IOException {
        transformer.registerSyntheticClass(SYNTHETIC_USING_HELPER, syntheticUsingHostNames());
        Path jar = dir.resolve(MOD_KEY);
        Files.write(jar, jarOf(Map.of(MOD_CLASS + ".class", classHolding(SYNTHETIC_USING_HELPER))));

        SyntheticEmbedder.embedIntoJar(jar, MOD_KEY, transformer);

        Map<String, byte[]> out = read(Files.readAllBytes(jar));
        String base = SyntheticEmbedder.embeddedBase(MOD_KEY);
        assertTrue(out.containsKey(base + HOST_NAMES + ".class"), "the synthetic's helper must travel too");
        assertEquals(List.of(), missingRetromodClasses(out));
    }

    @Test
    @DisplayName("a helper that reaches a registered synthetic travels only where that synthetic is registered")
    void helperReachingASyntheticNeedsItRegistered(@TempDir Path dir) throws IOException {
        Path without = dir.resolve("without.jar");
        Files.write(without, jarOf(Map.of(MOD_CLASS + ".class", classHolding(KEY_BINDING))));
        SyntheticEmbedder.embedIntoJar(without, "without.jar", transformer);
        assertTrue(SyntheticEmbedder.referencedClasses(read(Files.readAllBytes(without)).get(MOD_CLASS + ".class"))
                .contains(KEY_BINDING), "without its key mapping synthetic the shim stays shared");

        SyntheticEmbedder.registerClassResource(transformer, KEY_MAPPING, CompiledHelperEmbeddingTest.class);
        Path with = dir.resolve(MOD_KEY);
        Files.write(with, jarOf(Map.of(MOD_CLASS + ".class", classHolding(KEY_BINDING))));
        SyntheticEmbedder.embedIntoJar(with, MOD_KEY, transformer);
        Map<String, byte[]> out = read(Files.readAllBytes(with));
        String base = SyntheticEmbedder.embeddedBase(MOD_KEY);
        for (String copied : List.of(KEY_BINDING, KEY_MAPPING, "com/retromod/util/McReflect")) {
            assertTrue(out.containsKey(base + copied + ".class"), "missing embedded copy of " + copied);
        }
        assertEquals(List.of(), missingRetromodClasses(out));
    }

    @Test
    @DisplayName("every helper Retromod's runtime calls stays shared, so no mod copy splits its state")
    void runtimeCallsOnlySharedHelpers() throws Exception {
        Path classes = Path.of(CompiledHelpers.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Map<String, Set<String>> violations = new TreeMap<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/').replace(".class", "");
                if (!isRuntimeClass(name)) continue;
                for (String owner : calledOwners(Files.readAllBytes(file))) {
                    if (CompiledHelpers.travels(owner) && !CompiledHelpers.DUPLICABLE.contains(owner)) {
                        violations.computeIfAbsent(owner, k -> new TreeSet<>()).add(name);
                    }
                }
            }
        }
        assertEquals(Map.of(), violations,
                "Retromod's runtime calls these helpers, but mods would get their own copies; "
                        + "add them to CompiledHelpers.RUNTIME_ONLY or stop calling them from the runtime");
    }

    /** A mod class that calls the reported helpers and holds a runtime-only networking type. */
    private static byte[] modClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "distributor", "L" + PACKET_DISTRIBUTOR + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "lookup", "L" + DIRECTION_LOOKUP + ";", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ITEM_SAFETY, "noOpStatic", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MIXIN_EXTRAS_VERSION, "getVersion", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("fabric");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, VERSION_SPOOFER, "isModLoaded",
                "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod class with one field of the given type. */
    private static byte[] classHolding(String type) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "held", "L" + type + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A generated synthetic whose only method reads a compiled helper's field. */
    private static byte[] syntheticUsingHostNames() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SYNTHETIC_USING_HELPER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "gson", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, HOST_NAMES, "GSON", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Retromod classes a class in the jar names that the jar lacks and that are not kept shared on purpose. */
    private static List<String> missingRetromodClasses(Map<String, byte[]> jar) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : jar.entrySet()) {
            if (!entry.getKey().endsWith(".class")) continue;
            for (String ref : SyntheticEmbedder.referencedClasses(entry.getValue())) {
                if (!ref.startsWith("com/retromod/") || jar.containsKey(ref + ".class")) continue;
                if (ref.startsWith(NETWORK_SHIM)) continue;
                missing.add(entry.getKey() + " -> " + ref);
            }
        }
        return missing;
    }

    private static boolean isRuntimeClass(String name) {
        if (!SyntheticEmbedder.isRetromodClass(name) || CompiledHelpers.travels(name)) return false;
        String top = name.substring("com/retromod/".length(), name.indexOf('/', "com/retromod/".length()));
        return !MOD_SIDE_PACKAGES.contains(top);
    }

    /** Owners of the fields and methods a class calls or reads. */
    private static Set<String> calledOwners(byte[] bytes) {
        Set<String> owners = new TreeSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                        owners.add(owner);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String n, String d) {
                        owners.add(owner);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return owners;
    }

    private static byte[] jarOf(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, byte[]> read(byte[] jar) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (!entry.isDirectory()) entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
