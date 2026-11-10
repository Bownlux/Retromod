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
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Every class-transform path repairs the calls that stopped JEI, Balm and Farmer's Delight from constructing, in the mod and its nested library. */
class ConstructionRepairsPathParityTest {

    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String DATA_RESULT = "com/mojang/serialization/DataResult";
    private static final String UNIT_OBJECT = "(Ljava/lang/Object;)L" + CODEC + ";";
    private static final String UNIT_SUPPLIER = "(Ljava/util/function/Supplier;)L" + CODEC + ";";
    private static final String BOOK_TYPE = "net/minecraft/world/inventory/RecipeBookType";
    private static final String BOOK_HELPER = "com/retromod/legacyapi/forge/common/LegacyRecipeBookTypes";

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
    @CsvSource({"forge, runtime, 26.2", "forge, runtime, 26.3", "forge, transform, 26.2", "forge, batch, 26.2",
            "forge, aot, 26.2", "neoforge, runtime, 26.3", "neoforge, transform, 26.2",
            "fabric, runtime, 26.3", "fabric, runtime, 1.21.11", "fabric, transform, 26.3", "fabric, aot, 26.3"})
    @DisplayName("each path builds a stored id before storing it and sends the old unit codecs to MapCodec")
    void everyPathRepairsTheConstructionCalls(String loader, String path, String host) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("construct.jar");
        Files.write(input, modJar(loader));

        Path output = translate(loader, path, host, input);

        for (Map.Entry<String, Fixture> entry : classes(output, loader).entrySet()) {
            String where = entry.getKey() + " (" + loader + " " + path + " on " + host + ")";
            ClassNode node = entry.getValue().node();
            for (MethodNode method : node.methods) {
                new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
            }
            assertStoredIdBuiltFirst(method(node, "create"), where);
            assertUnitCodecsMoved(method(node, "units"), where);
            assertEquals(List.of("CHECKCAST " + DATA_RESULT + "$Error", DATA_RESULT + "$Error.message"),
                    errorReads(method(node, "message")), where);
            // Fabric mods never had Forge's RecipeBookType.create.
            if (!loader.equals("fabric")) assertBookTypeHelper(method(node, "bookType"), entry.getValue().entries(), where);
        }
    }

    /** JEI's {@code NEW, DUP, ASTORE} id: the factory runs, then its result is stored. */
    private static void assertStoredIdBuiltFirst(MethodNode create, String where) {
        int factory = -1;
        int store = -1;
        for (AbstractInsnNode insn : create.instructions) {
            int index = create.instructions.indexOf(insn);
            if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC && factory < 0) factory = index;
            if (insn.getOpcode() == ASTORE && store < 0) store = index;
            if (insn instanceof TypeInsnNode type && type.getOpcode() == NEW) {
                assertFalse(type.desc.endsWith("/Identifier") || type.desc.endsWith("/ResourceLocation")
                        || type.desc.equals("net/minecraft/class_2960"), where + " still allocates the id: " + type.desc);
            }
        }
        assertTrue(factory >= 0 && factory < store, where + ": the id factory must run before its result is stored");
    }

    private static void assertUnitCodecsMoved(MethodNode units, String where) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : units.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc + " itf=" + call.itf);
            if (insn instanceof InvokeDynamicInsnNode indy) {
                for (Object argument : indy.bsmArgs) {
                    if (argument instanceof Handle handle && !handle.getName().startsWith("lambda$")) {
                        calls.add("ref " + handle.getOwner() + "." + handle.getName() + handle.getDesc() + " itf=" + handle.isInterface());
                    }
                }
            }
        }
        assertEquals(List.of(MAP_CODEC + ".unitCodec" + UNIT_OBJECT + " itf=false",
                MAP_CODEC + ".unitCodec" + UNIT_SUPPLIER + " itf=false",
                "ref " + MAP_CODEC + ".unitCodec" + UNIT_OBJECT + " itf=false"), calls, where);
    }

    private static List<String> errorReads(MethodNode message) {
        List<String> reads = new ArrayList<>();
        for (AbstractInsnNode insn : message.instructions) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == CHECKCAST) reads.add("CHECKCAST " + type.desc);
            if (insn instanceof MethodInsnNode call && call.name.equals("message")) reads.add(call.owner + "." + call.name);
        }
        return reads;
    }

    /** Farmer's Delight's book type comes from the rebuilt helper, which travels in the same jar. */
    private static void assertBookTypeHelper(MethodNode bookType, Set<String> entries, String where) {
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : bookType.instructions) {
            if (insn instanceof MethodInsnNode method && method.name.equals("create")) call = method;
        }
        assertNotNull(call, where);
        assertTrue(call.owner.startsWith(SyntheticEmbedder.PREFIX) && call.owner.endsWith(BOOK_HELPER),
                where + " must call the rebuilt helper: " + call.owner);
        assertTrue(entries.contains(call.owner + ".class"), where + ": the helper travels in the jar");
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
                CliHarness.transform(input, out.resolve("construct.jar"), host);
                yield out.resolve("construct.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("construct.jar"), host);
                yield out.resolve("construct.jar");
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

    /** One fixture class and the entry names of the jar that holds it. */
    private record Fixture(ClassNode node, Set<String> entries) {}

    /** The fixture class of the mod and of its nested library, as the output jar holds them. */
    private static Map<String, Fixture> classes(Path output, String loader) throws Exception {
        Map<String, Fixture> found = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            found.put("mod/Construct", new Fixture(read(jar, "mod/Construct"), entries(jar)));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                JarEntry entry = jar.getJarEntry(nestedPath(loader));
                assertNotNull(entry, "the nested library is missing from " + output.getFileName());
                Files.write(nested, jar.getInputStream(entry).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    found.put("nested:lib/LibConstruct", new Fixture(read(lib, "lib/LibConstruct"), entries(lib)));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static Set<String> entries(JarFile jar) {
        return jar.stream().map(JarEntry::getName).collect(java.util.stream.Collectors.toSet());
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

    /** A 1.20.1 mod for {@code loader} (1.20.4 for NeoForge) with the four calls, and a nested library mod with the same. */
    private static byte[] modJar(String loader) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put(metadataPath(loader), metadata(loader, "constructlib", false));
        lib.put("lib/LibConstruct.class", constructClass("lib/LibConstruct", loader));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(metadataPath(loader), metadata(loader, "construct", true));
        mod.put("mod/Construct.class", constructClass("mod/Construct", loader));
        mod.put(nestedPath(loader), LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    private static String metadataPath(String loader) {
        return switch (loader) {
            case "fabric" -> "fabric.mod.json";
            default -> "META-INF/mods.toml";
        };
    }

    private static byte[] metadata(String loader, String modId, boolean nestsLibrary) {
        String text = switch (loader) {
            case "fabric" -> "{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0.0\","
                    + "\"depends\":{\"minecraft\":\"1.20.1\",\"fabricloader\":\">=0.14.0\"}"
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

    /** The JEI id, Balm's unit codecs, an error message read, and Farmer's Delight's book type, by {@code loader}'s names. */
    private static byte[] constructClass(String name, String loader) {
        String id = loader.equals("fabric") ? "net/minecraft/class_2960" : "net/minecraft/resources/ResourceLocation";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);

        // static Object create(String, String, Object): new Holder(new ResourceLocation(a, b), c), stored as JEI does.
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, id);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, id, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        mv.visitTypeInsn(NEW, name + "$Holder");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, name + "$Holder", "<init>", "(L" + id + ";Ljava/lang/Object;)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // static void units(Object, Supplier): Codec.unit(value), Codec.unit(supplier) and Codec::unit.
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "units", "(Ljava/lang/Object;Ljava/util/function/Supplier;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, CODEC, "unit", UNIT_OBJECT, true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, CODEC, "unit", UNIT_SUPPLIER, true);
        mv.visitInsn(POP);
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", metafactory,
                Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(H_INVOKESTATIC, CODEC, "unit", UNIT_OBJECT, true),
                Type.getType(UNIT_OBJECT));
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // static String message(DataResult): ((PartialResult) result.error().get()).message()
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "message", "(L" + DATA_RESULT + ";)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, DATA_RESULT, "error", "()Ljava/util/Optional;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, DATA_RESULT + "$PartialResult");
        mv.visitMethodInsn(INVOKEVIRTUAL, DATA_RESULT + "$PartialResult", "message", "()Ljava/lang/String;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // static Object bookType(): RecipeBookType.create("COOKING"), as Farmer's Delight's static initializer calls it.
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "bookType", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitLdcInsn("COOKING");
        mv.visitMethodInsn(INVOKESTATIC, BOOK_TYPE, "create", "(Ljava/lang/String;)L" + BOOK_TYPE + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
