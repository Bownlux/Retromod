/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.common.LegacyRegistryMapCodecBridge;
import com.retromod.shim.forge.RegistryIdBridgeSynthetic;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

/** Every path converts what a Forge 1.20.1 mod and its nested library register into a modifier serializer registry, and merges their biomes' carvers. */
class RegistryMapCodecPathParityTest {

    private static final String KEYS = "net/minecraftforge/registries/ForgeRegistries$Keys";
    private static final String DEFERRED = "net/minecraftforge/registries/DeferredRegister";
    private static final String EVENT = "net/minecraftforge/registries/RegisterEvent";
    private static final String L_KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String NESTED = "META-INF/jarjar/lib.jar";

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

    @ParameterizedTest(name = "{1} on {0} {2}")
    @CsvSource({"forge, runtime, 1.21.1", "forge, runtime, 26.2", "forge, runtime, 26.3", "forge, transform, 26.2", "forge, transform, 26.3",
            "forge, batch, 26.2", "forge, aot, 26.2", "forge, aot, 26.3", "neoforge, runtime, 26.2",
            "neoforge, runtime, 26.3", "neoforge, transform, 26.3"})
    @DisplayName("the register event and deferred register reach the conversion, embedded, in the mod and its nested library")
    void everyPathConverts(String loader, String path, String host) throws Exception {
        Path output = translate(loader, path, host, writeMod("1.20.1"));

        try (JarFile jar = new JarFile(output.toFile())) {
            Path nested = Files.createTempFile(dir, "nested-", ".jar");
            Files.write(nested, jar.getInputStream(jar.getJarEntry(NESTED)).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                for (Map.Entry<String, JarFile> part : Map.of("mod", jar, "lib", lib).entrySet()) {
                    String where = part.getKey() + " (" + loader + " " + path + " on " + host + ")";
                    ClassNode registration = read(part.getValue(), part.getKey() + "/Registration");

                    String each = call(method(registration, "event"), "registerEach");
                    assertTrue(each.startsWith(SyntheticEmbedder.PREFIX)
                            && each.contains(LegacyRegistryMapCodecBridge.CLASS + ".registerEach"), where + ": " + each);
                    String bridge = owner(each);
                    ClassNode value = read(jar, lib, bridge);
                    // A unit codec, such as Balm's, has no "value" key, so the whole map must be read too.
                    assertTrue(calls(method(value, "mapCodec")).contains("com/mojang/serialization/MapCodec.assumeMapUnsafe"
                            + "(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/MapCodec;"), where);
                    String helper = owner(call(method(value, "serializer"), "serializer"));
                    assertTrue(helper.startsWith(SyntheticEmbedder.PREFIX)
                            && helper.endsWith(RegistryIdBridgeSynthetic.LOOT_MODIFIER_CODECS), where + ": " + helper);
                    assertNotNull(read(jar, lib, helper), where + ": the embedded helper travels with the value class");

                    if (loader.equals("forge") && RetromodVersion.compareMcVersions(host, "1.21.11") < 0) {
                        // Before Forge 1.21.11 there is no register wrapper, so the call converts on its own.
                        String deferred = call(method(registration, "deferred"), "registerDeferred");
                        assertTrue(owner(deferred).endsWith(LegacyRegistryMapCodecBridge.CLASS), where + ": " + deferred);
                    } else {
                        // The deferred register goes through the loader's register wrapper, which converts first.
                        String wrapperName = loader.equals("forge") ? "registerForge" : "register";
                        String wrapper = owner(call(method(registration, "deferred"), wrapperName));
                        MethodNode register = method(read(jar, lib, wrapper), wrapperName);
                        assertTrue(owner(call(register, "serializer")).endsWith(LegacyRegistryMapCodecBridge.CLASS), where);
                    }

                    // 1.21.2 is the first host that reads one carver list.
                    boolean oneList = RetromodVersion.compareMcVersions(host, "1.21.2") >= 0;
                    assertEquals(oneList ? new JsonArray() : new JsonObject(),
                            carvers(part.getValue(), part.getKey()), where + ": the biome's carvers");
                }
            }
        }
    }

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({"runtime, 26.1, true", "transform, 26.1, true", "runtime, 26.2, false", "transform, 26.3, false"})
    @DisplayName("a mod built for 1.21.1 keeps the loader's register event until 26.2 makes processor types map codecs")
    void newerModsKeepTheEventBefore262(String path, String host, boolean keeps) throws Exception {
        Path output = translate("forge", path, host, writeMod("1.21.1"));
        try (JarFile jar = new JarFile(output.toFile())) {
            List<String> calls = calls(method(read(jar, "mod/Registration"), "event"));
            assertEquals(keeps, calls.contains(EVENT + ".register(" + L_KEY + "Ljava/util/function/Consumer;)V"),
                    calls.toString());
            assertEquals(!keeps, calls.stream().anyMatch(call -> call.contains(".registerEach(")), calls.toString());
        }
    }

    /** Translates {@code input} for a {@code loader} host through one path and returns the output jar. */
    private Path translate(String loader, String path, String host, Path input) throws Exception {
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader);
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> {
                RetromodVersion.TARGET_MC_VERSION = host;
                transformer.clearRedirectsForTesting();
                Set<String> types = loader.equals("forge") ? Set.of("forge", "common") : Set.of("neoforge", "forge", "common");
                for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
                    if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                        ShimRegistration.register(shim, transformer);
                    }
                }
                SrgToMojangMapper.getInstance().applyTo(transformer);
                IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
                yield new ForgeModTransformer(host).transformMod(input, out);
            }
            case "transform" -> {
                CliHarness.transform(input, out.resolve("mapmod.jar"), host);
                yield out.resolve("mapmod.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("mapmod.jar"), host);
                yield out.resolve("mapmod.jar");
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

    /** A Forge mod built for {@code version}, with the registrations and a placeholder biome, nesting a library with the same. */
    private Path writeMod(String version) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>(fixture("lib", version));
        Map<String, byte[]> mod = new LinkedHashMap<>(fixture("mod", version));
        mod.put(NESTED, LoaderApiFixtures.jar(lib));
        Path input = Files.createDirectories(dir.resolve("in")).resolve("mapmod-" + version + ".jar");
        Files.write(input, LoaderApiFixtures.jar(mod));
        return input;
    }

    private static Map<String, byte[]> fixture(String pkg, String version) {
        String next = version.startsWith("1.20") ? "1.21" : "1.22";
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"map" + pkg
                + "\"\nversion=\"1.0.0\"\n[[dependencies.map" + pkg + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[" + version + "," + next + ")\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/mods.toml", toml.getBytes(StandardCharsets.UTF_8));
        entries.put(pkg + "/Registration.class", registration(pkg + "/Registration"));
        entries.put("data/map" + pkg + "/worldgen/biome/placeholder.json",
                "{\"carvers\":{},\"downfall\":0.5,\"features\":[],\"has_precipitation\":false,\"temperature\":0.5}"
                        .getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    /** Balm's deferred register and Cobblemon's register event, for the biome modifier serializers. */
    private static byte[] registration(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor deferred = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "deferred", "(Ljava/util/function/Supplier;)Ljava/lang/Object;", null, null);
        deferred.visitCode();
        deferred.visitFieldInsn(GETSTATIC, KEYS, "BIOME_MODIFIER_SERIALIZERS", L_KEY);
        deferred.visitLdcInsn("mapmod");
        deferred.visitMethodInsn(INVOKESTATIC, DEFERRED, "create", "(" + L_KEY + "Ljava/lang/String;)L" + DEFERRED + ";", false);
        deferred.visitLdcInsn("modifier");
        deferred.visitVarInsn(ALOAD, 0);
        deferred.visitMethodInsn(INVOKEVIRTUAL, DEFERRED, "register",
                "(Ljava/lang/String;Ljava/util/function/Supplier;)Lnet/minecraftforge/registries/RegistryObject;", false);
        deferred.visitInsn(ARETURN);
        deferred.visitMaxs(0, 0);
        deferred.visitEnd();
        MethodVisitor event = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "event", "(L" + EVENT + ";Ljava/util/function/Consumer;)V", null, null);
        event.visitCode();
        event.visitVarInsn(ALOAD, 0);
        event.visitFieldInsn(GETSTATIC, KEYS, "BIOME_MODIFIER_SERIALIZERS", L_KEY);
        event.visitVarInsn(ALOAD, 1);
        event.visitMethodInsn(INVOKEVIRTUAL, EVENT, "register", "(" + L_KEY + "Ljava/util/function/Consumer;)V", false);
        event.visitInsn(RETURN);
        event.visitMaxs(0, 0);
        event.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static JsonElement carvers(JarFile jar, String pkg) throws Exception {
        JarEntry entry = jar.getJarEntry("data/map" + pkg + "/worldgen/biome/placeholder.json");
        assertNotNull(entry, "the biome is missing from " + jar.getName());
        String json = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        return JsonParser.parseString(json).getAsJsonObject().get("carvers");
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

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    /** The one call to a method named {@code name}. */
    private static String call(MethodNode method, String name) {
        return calls(method).stream().filter(call -> call.contains("." + name + "(")).findFirst()
                .orElseThrow(() -> new AssertionError(method.name + " calls no " + name + ": " + calls(method)));
    }

    private static String owner(String call) {
        return call.substring(0, call.indexOf('.'));
    }
}
