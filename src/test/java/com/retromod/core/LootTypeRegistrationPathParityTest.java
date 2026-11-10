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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

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

/**
 * Every path gives a mod and its nested library loot type stand-ins that are map codecs and processor types both views,
 * and reads a Forge 1.20.1 mod's frame delta, as Jade's overlay asks for it, through the delta tracker.
 */
class LootTypeRegistrationPathParityTest {

    private static final String LOOT = "net/minecraft/world/level/storage/loot/";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String SERIALIZER = LOOT + "Serializer";
    private static final String STAND_INS = "com/retromod/shim/common/embedded/";
    private static final String REGISTRIES = "net/minecraft/core/registries/Registries";
    private static final String L_KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String PROCESSOR_TYPE = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureProcessorType";

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
    @CsvSource({"forge, runtime, 26.2", "forge, runtime, 26.3", "forge, transform, 26.2", "forge, transform, 26.3",
            "forge, batch, 26.2", "forge, aot, 26.2", "forge, aot, 26.3", "neoforge, runtime, 26.2",
            "neoforge, runtime, 26.3", "neoforge, transform, 26.3", "neoforge, aot, 26.2", "fabric, runtime, 26.2",
            "fabric, runtime, 26.3", "fabric, transform, 26.3", "fabric, aot, 26.2"})
    @DisplayName("the loot types an old mod builds are embedded map codec stand-ins, in the mod and its nested library")
    void everyPathBuildsMapCodecStandIns(String loader, String path, String host) throws Exception {
        Path output = translate(loader, path, host, writeMod(loader));

        try (JarFile jar = new JarFile(output.toFile())) {
            Path nested = Files.createTempFile(dir, "nested-", ".jar");
            Files.write(nested, jar.getInputStream(jar.getJarEntry(nestedPath(loader))).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                for (Map.Entry<String, JarFile> part : Map.of("mod", jar, "lib", lib).entrySet()) {
                    String where = part.getKey() + " (" + loader + " " + path + " on " + host + ")";
                    ClassNode registration = read(part.getValue(), part.getKey() + "/Registration");
                    for (String standIn : standInsBuilt(loader)) {
                        String embedded = builtStandIn(jar, lib, registration, standIn, where);
                        ClassNode node = read(jar, lib, embedded);
                        assertEquals(MAP_CODEC, node.superName, where + ": " + embedded + " is the codec it holds");
                    }
                    assertNoDeletedRecordNamed(part.getValue(), where);
                    if (!loader.equals("fabric")) assertProcessorViews(jar, lib, registration, where);
                    if (loader.equals("forge")) {
                        assertFrameDeltaBridged(jar, lib, read(part.getValue(), part.getKey() + "/Overlay"), where);
                    }
                }
            }
        }
    }

    /** The stand-in a factory method builds, directly or through the embedded 1.20.1 serializer factory. */
    private static String builtStandIn(JarFile jar, JarFile lib, ClassNode registration, String standIn, String where)
            throws Exception {
        String simple = standIn.substring(standIn.lastIndexOf('/') + 1);
        String factory = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        MethodNode method = method(registration, factory);
        String created = created(method);
        if (created == null) {
            // new LootItemFunctionType(serializer) became LegacyLootTypes.functionType(serializer).
            MethodInsnNode call = calls(method).stream().filter(c -> c.owner.endsWith("LegacyLootTypes")).findFirst()
                    .orElseThrow(() -> new AssertionError(where + ": " + factory + " builds no stand-in"));
            assertTrue(call.owner.startsWith(SyntheticEmbedder.PREFIX), where + ": " + call.owner);
            created = created(method(read(jar, lib, call.owner), call.name));
        }
        assertNotNull(created, where + ": " + factory);
        assertTrue(created.startsWith(SyntheticEmbedder.PREFIX) && created.endsWith(standIn), where + ": " + created);
        return created;
    }

    /** On 26.2 and 26.3 an old processor type becomes an embedded map codec that is also a processor type, for its holder. */
    private static void assertProcessorViews(JarFile jar, JarFile lib, ClassNode registration, String where) throws Exception {
        MethodInsnNode each = calls(method(registration, "event")).stream()
                .filter(c -> c.name.equals("registerEach")).findFirst()
                .orElseThrow(() -> new AssertionError(where + ": the register event skips the conversion: "
                        + calls(method(registration, "event")).stream().map(c -> c.owner + "." + c.name).toList()));
        ClassNode value = read(jar, lib, each.owner);
        MethodNode of = method(value, "of");
        assertTrue(calls(of).stream().anyMatch(c -> c.name.equals("processorTypeCodec")), where);
        String view = created(of);
        assertTrue(view != null && view.startsWith(SyntheticEmbedder.PREFIX), where + ": " + view);
        ClassNode viewNode = read(jar, lib, view);
        assertEquals(MAP_CODEC, viewNode.superName, where);
        assertTrue(viewNode.interfaces.contains(PROCESSOR_TYPE), where + ": " + viewNode.interfaces);
        // The deferred register goes through the loader's register wrapper, which converts the supplier's value.
        MethodInsnNode wrapper = calls(method(registration, "deferred")).stream()
                .filter(c -> c.owner.startsWith(SyntheticEmbedder.PREFIX) && c.name.startsWith("register")).findFirst()
                .orElseThrow(() -> new AssertionError(where + ": the deferred register skips the wrapper"));
        assertTrue(calls(method(read(jar, lib, wrapper.owner), wrapper.name)).stream()
                .anyMatch(c -> c.name.equals("serializer") && c.owner.equals(each.owner)), where);
    }

    /** Jade's {@code getDeltaFrameTime()} goes to the embedded frame time helper. */
    private static void assertFrameDeltaBridged(JarFile jar, JarFile lib, ClassNode overlay, String where) throws Exception {
        MethodInsnNode delta = calls(method(overlay, "fade")).stream()
                .filter(c -> c.getOpcode() == INVOKESTATIC && c.desc.equals("(Lnet/minecraft/client/Minecraft;)F"))
                .findFirst().orElseThrow(() -> new AssertionError(where + ": getDeltaFrameTime stays on Minecraft"));
        assertTrue(delta.owner.startsWith(SyntheticEmbedder.PREFIX) && delta.name.equals("getDeltaFrameTime"),
                where + ": " + delta.owner + "." + delta.name);
        assertNotNull(read(jar, lib, delta.owner), where + ": the frame time helper travels with the call");
    }

    private static void assertNoDeletedRecordNamed(JarFile jar, String where) throws Exception {
        for (JarEntry entry : java.util.Collections.list(jar.entries())) {
            if (!entry.getName().endsWith(".class")) continue;
            byte[] bytes = jar.getInputStream(entry).readAllBytes();
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            for (String record : List.of("functions/LootItemFunctionType", "predicates/LootItemConditionType",
                    "entries/LootPoolEntryType", "net/minecraft/class_5339", "net/minecraft/class_5342",
                    "net/minecraft/class_5338")) {
                String name = record.startsWith("net/") ? record : LOOT + record;
                assertFalse(text.contains(name), where + ": " + entry.getName() + " still names " + name);
            }
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
                CliHarness.transform(input, out.resolve("lootmod.jar"), host);
                yield out.resolve("lootmod.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("lootmod.jar"), host);
                yield out.resolve("lootmod.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                try (var jars = Files.list(out)) {
                    yield jars.filter(jar -> jar.toString().endsWith(".jar")).findFirst().orElseThrow();
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

    /** The stand-ins a fixture builds: Forge 1.20.1 through its serializers, 1.21.1 mods with map codecs. */
    private static List<String> standInsBuilt(String loader) {
        List<String> built = new ArrayList<>(List.of(STAND_INS + "LootItemFunctionType", STAND_INS + "LootItemConditionType"));
        if (!loader.equals("forge")) built.add(STAND_INS + "LootPoolEntryType");
        return built;
    }

    private Path writeMod(String loader) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>(fixture(loader, "lib"));
        Map<String, byte[]> mod = new LinkedHashMap<>(fixture(loader, "mod"));
        mod.put(nestedPath(loader), LoaderApiFixtures.jar(lib));
        Path input = Files.createDirectories(dir.resolve("in")).resolve("lootmod-" + loader + ".jar");
        Files.write(input, LoaderApiFixtures.jar(mod));
        return input;
    }

    private static String nestedPath(String loader) {
        return loader.equals("fabric") ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    private static Map<String, byte[]> fixture(String loader, String pkg) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String id = "loot" + pkg;
        String metadata = switch (loader) {
            case "fabric" -> "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\","
                    + "\"depends\":{\"minecraft\":\"1.21.1\",\"fabricloader\":\">=0.15.0\"}"
                    + (pkg.equals("mod") ? ",\"jars\":[{\"file\":\"META-INF/jars/lib.jar\"}]" : "") + "}";
            case "forge" -> "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id
                    + "\"\nversion=\"1.0.0\"\n[[dependencies." + id + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                    + "versionRange=\"[1.20.1,1.21)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
            default -> "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id
                    + "\"\nversion=\"1.0.0\"\n[[dependencies." + id + "]]\nmodId=\"minecraft\"\ntype=\"required\"\n"
                    + "versionRange=\"[1.21.1,1.22)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
        };
        String metadataPath = switch (loader) {
            case "fabric" -> "fabric.mod.json";
            case "forge" -> "META-INF/mods.toml";
            default -> "META-INF/neoforge.mods.toml";
        };
        entries.put(metadataPath, metadata.getBytes(StandardCharsets.UTF_8));
        entries.put(pkg + "/Registration.class", registration(loader, pkg + "/Registration"));
        // Only a mod built before 1.21 calls getDeltaFrameTime, which 1.21 removed.
        if (loader.equals("forge")) entries.put(pkg + "/Overlay.class", overlay(pkg + "/Overlay"));
        return entries;
    }

    /**
     * Sophisticated Backpacks' and Lootr's loot types, Cataclysm's processor types through a deferred register, and
     * Cobblemon's through the register event. Forge 1.20.1 builds the types around its serializers; 1.21.1 around map
     * codecs, in intermediary names on Fabric.
     */
    private static byte[] registration(String loader, String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        boolean fabric = loader.equals("fabric");
        String functionType = fabric ? "net/minecraft/class_5339" : LOOT + "functions/LootItemFunctionType";
        String conditionType = fabric ? "net/minecraft/class_5342" : LOOT + "predicates/LootItemConditionType";
        String entryType = fabric ? "net/minecraft/class_5338" : LOOT + "entries/LootPoolEntryType";
        String built = loader.equals("forge") ? SERIALIZER : MAP_CODEC;
        factory(cw, "lootItemFunctionType", functionType, built);
        factory(cw, "lootItemConditionType", conditionType, built);
        if (!loader.equals("forge")) factory(cw, "lootPoolEntryType", entryType, built);
        if (!fabric) registrations(cw, loader);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Jade 1.20.1's overlay: {@code static float fade() { return Minecraft.getInstance().getDeltaFrameTime(); }}, in SRG names. */
    private static byte[] overlay(String name) {
        String minecraft = "net/minecraft/client/Minecraft";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "fade", "()F", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, minecraft, "m_91087_", "()L" + minecraft + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, minecraft, "m_91297_", "()F", false);
        mv.visitInsn(FRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static Object name(Arg arg) { return new Type(arg); }} */
    private static void factory(ClassWriter cw, String name, String type, String argument) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(L" + argument + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, type);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, type, "<init>", "(L" + argument + ";)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** A processor type through the loader's deferred register, and through its register event. */
    private static void registrations(ClassWriter cw, String loader) {
        boolean forge = loader.equals("forge");
        String registries = forge ? "net/minecraftforge/registries/" : "net/neoforged/neoforge/registries/";
        String deferred = registries + "DeferredRegister";
        String holder = forge ? registries + "RegistryObject" : registries + "DeferredHolder";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "deferred", "(Ljava/util/function/Supplier;)Ljava/lang/Object;",
                null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, REGISTRIES, "STRUCTURE_PROCESSOR", L_KEY);
        mv.visitLdcInsn("lootmod");
        mv.visitMethodInsn(INVOKESTATIC, deferred, "create", "(" + L_KEY + "Ljava/lang/String;)L" + deferred + ";", false);
        mv.visitLdcInsn("processor");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, deferred, "register", "(Ljava/lang/String;Ljava/util/function/Supplier;)L" + holder + ";",
                false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        String event = registries + "RegisterEvent";
        MethodVisitor ev = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "event", "(L" + event + ";Ljava/util/function/Consumer;)V",
                null, null);
        ev.visitCode();
        ev.visitVarInsn(ALOAD, 0);
        ev.visitFieldInsn(GETSTATIC, REGISTRIES, "STRUCTURE_PROCESSOR", L_KEY);
        ev.visitVarInsn(ALOAD, 1);
        ev.visitMethodInsn(INVOKEVIRTUAL, event, "register", "(" + L_KEY + "Ljava/util/function/Consumer;)V", false);
        ev.visitInsn(RETURN);
        ev.visitMaxs(0, 0);
        ev.visitEnd();
    }

    /** A class from the nested library, or from the outer jar where the embedder put it. */
    private static ClassNode read(JarFile jar, JarFile lib, String name) throws Exception {
        return lib.getJarEntry(name + ".class") != null ? read(lib, name) : read(jar, name);
    }

    private static ClassNode read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name));
    }

    /** The class the method's first {@code new} builds, or null. */
    private static String created(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == NEW) return type.desc;
        }
        return null;
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }
}
