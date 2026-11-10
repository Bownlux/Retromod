/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.common.LegacyComponentJsonBridge.Serializer;
import com.retromod.shim.fabric.Pre1_20_5ComponentJsonBridge;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayOutputStream;
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
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Saint's Dragons (Forge 1.20.1) reads its dialogue text with {@code Component.Serializer.fromJson(JsonElement)},
 * which 1.20.5 replaced with a form that takes registries, so NeoForge 1.21.1 failed to load data packs.
 */
class LegacyComponentJsonBridgeTest {

    private static final String SERIALIZER = LegacyComponentJsonBridge.OLD;
    private static final String STYLE = LegacyComponentJsonBridge.OLD_STYLE;
    private static final String JSON = LegacyComponentJsonBridge.JSON;
    private static final String STYLE_JSON = LegacyComponentJsonBridge.STYLE;
    private static final String COMPONENT = "net/minecraft/network/chat/Component";
    private static final String MUTABLE = "net/minecraft/network/chat/MutableComponent";
    private static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    private static final String GSON_ELEMENT = "com/google/gson/JsonElement";
    private static final String OLD_RANGE = "[1.20.1,1.20.2)";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private final boolean savedForge = McReflect.isForceForge();

    @TempDir
    Path tempDir;

    @BeforeEach
    void clear() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        McReflect.setForceForge(savedForge);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    // ---- host shapes ----

    @Test
    @DisplayName("The host's own classes decide: the old statics, registry-taking statics, or no serializer at all")
    void hostClassesDecide() {
        ClassNode serialization = node("net/minecraft/network/chat/ComponentSerialization", null);
        ClassNode old = node(SERIALIZER, new MethodNode(ACC_PUBLIC | ACC_STATIC, "fromJson",
                "(Ljava/lang/String;)L" + MUTABLE + ";", null, null));
        ClassNode registries = node(SERIALIZER, new MethodNode(ACC_PUBLIC | ACC_STATIC, "fromJson",
                "(Ljava/lang/String;L" + PROVIDER + ";)L" + MUTABLE + ";", null, null));
        assertEquals(Serializer.STATICS, LegacyComponentJsonBridge.serializer(
                Map.of("net/minecraft/network/chat/ComponentSerialization", serialization, SERIALIZER, old)::get, "1.21.1"));
        assertEquals(Serializer.TAKES_REGISTRIES, LegacyComponentJsonBridge.serializer(
                Map.of("net/minecraft/network/chat/ComponentSerialization", serialization, SERIALIZER, registries)::get, "1.21.1"));
        assertEquals(Serializer.GONE, LegacyComponentJsonBridge.serializer(
                Map.of("net/minecraft/network/chat/ComponentSerialization", serialization)::get, "1.21.6"));

        ClassNode adapter = node(STYLE, null);
        adapter.interfaces.add("com/google/gson/JsonDeserializer");
        assertTrue(LegacyComponentJsonBridge.styleIsAdapter(
                Map.of("net/minecraft/network/chat/ComponentSerialization", serialization, STYLE, adapter)::get, "1.21.1"));
        assertFalse(LegacyComponentJsonBridge.styleIsAdapter(
                Map.of("net/minecraft/network/chat/ComponentSerialization", serialization, STYLE, node(STYLE, null))::get, "1.21.1"));
    }

    @Test
    @DisplayName("Offline, the target version decides: 1.20.3 ended both adapters, 1.20.5 took registries, 1.21.6 removed the class")
    void offlineVersionsDecide() {
        assertEquals(Serializer.OLD, LegacyComponentJsonBridge.serializer(name -> null, "1.20.2"));
        assertEquals(Serializer.STATICS, LegacyComponentJsonBridge.serializer(name -> null, "1.20.4"));
        assertEquals(Serializer.TAKES_REGISTRIES, LegacyComponentJsonBridge.serializer(name -> null, "1.21.1"));
        assertEquals(Serializer.TAKES_REGISTRIES, LegacyComponentJsonBridge.serializer(name -> null, "1.21.5"));
        assertEquals(Serializer.GONE, LegacyComponentJsonBridge.serializer(name -> null, "1.21.6"));
        assertEquals(Serializer.GONE, LegacyComponentJsonBridge.serializer(name -> null, "26.3"));
        assertTrue(LegacyComponentJsonBridge.styleIsAdapter(name -> null, "1.20.2"));
        assertFalse(LegacyComponentJsonBridge.styleIsAdapter(name -> null, "1.20.3"));
    }

    @Test
    @DisplayName("The shim registers from 1.20.5, never on a 1.20.4 host, and leaves Fabric before 26.1 to its intermediary bridge")
    void shimGate() {
        LegacyComponentJsonShim shim = new LegacyComponentJsonShim();
        assertFalse(ShimRegistry.isAvailableOnHost(shim, "1.20.4"));
        for (String host : List.of("1.20.5", "1.21.1", "1.21.5", "26.3")) assertTrue(ShimRegistry.isAvailableOnHost(shim, host), host);
        onHost("fabric", "1.21.1");
        shim.registerRedirects(transformer);
        assertTrue(transformer.getClassRedirects().isEmpty(), "Mojang names never reach a Fabric 1.21.1 host");
    }

    // ---- transform ----

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"neoforge, 1.21.1", "forge, 1.21.1", "neoforge, 1.21.5", "neoforge, 26.3", "fabric, 26.3"})
    @DisplayName("Every old member a 1.20.1 mod calls goes to the stand-ins: statics, the Gson adapters, and a subclass")
    void oldModReachesTheStandIns(String loader, String host) throws Exception {
        onHost(loader, host);
        new LegacyComponentJsonShim().registerRedirects(transformer);
        Map<String, byte[]> out = transformOld(OLD_RANGE);

        List<String> statics = BridgeHarness.calls(out.get("mod/Texts"), "calls");
        assertEquals(List.of(JSON + ".fromJson(Ljava/lang/String;)L" + MUTABLE + ";",
                JSON + ".fromJson(L" + GSON_ELEMENT + ";)L" + MUTABLE + ";",
                JSON + ".fromJsonLenient(Ljava/lang/String;)L" + MUTABLE + ";",
                JSON + ".toJson(L" + COMPONENT + ";)Ljava/lang/String;",
                JSON + ".toJsonTree(L" + COMPONENT + ";)L" + GSON_ELEMENT + ";",
                JSON + ".toStableJson(L" + COMPONENT + ";)Ljava/lang/String;"), statics);
        List<String> adapters = news(out.get("mod/Texts"), "<clinit>");
        assertEquals(List.of(JSON, STYLE_JSON), adapters.stream().filter(n -> n.startsWith(RebuiltApiClasses.ROOT)).toList(),
                "the two Gson adapters are the stand-ins");
        assertEquals(JSON, superName(out.get("mod/Texts$Custom")), "a mod's own adapter extends the stand-in");
        assertTrue(transformer.getSyntheticClasses().containsKey(JSON) && transformer.getSyntheticClasses().containsKey(STYLE_JSON));
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyEnchantmentRegistryShim.HELPER),
                "the stand-in reads the world's registries through the shared view, which travels with it");
    }

    @Test
    @DisplayName("On 1.20.5 to 1.21.5 a mod built for them keeps the host's registry-taking serializer; on 1.21.6 it has none to keep")
    void newerModsKeepTheHostWhileItExists() {
        onHost("neoforge", "1.21.1");
        new LegacyComponentJsonShim().registerRedirects(transformer);
        transformer.setModSourceVersion("[1.21.1,1.21.2)");
        assertEquals(List.of(SERIALIZER + ".fromJson(Ljava/lang/String;L" + PROVIDER + ";)L" + MUTABLE + ";"),
                BridgeHarness.calls(transformer.transformClass(providerCall(), "mod/New"), "read"));

        onHost("neoforge", "26.3");
        new LegacyComponentJsonShim().registerRedirects(transformer);
        transformer.setModSourceVersion("[1.21.1,1.21.2)");
        assertEquals(List.of(JSON + ".fromJson(Ljava/lang/String;L" + PROVIDER + ";)L" + MUTABLE + ";"),
                BridgeHarness.calls(transformer.transformClass(providerCall(), "mod/New"), "read"));
    }

    @Test
    @DisplayName("A Fabric target from 1.20.5 to 1.21.5 takes the intermediary bridge offline as in game; 1.21.6 does not")
    void fabricOfflineRegistration() {
        String serializer = "net/minecraft/class_2561$class_2562";
        Pre1_20_5ComponentJsonBridge.registerForTarget(transformer, "1.21.1");
        assertNotNull(transformer.getMethodRedirects().get(new RetromodTransformer.MethodKey(serializer, "method_10877",
                "(Ljava/lang/String;)Lnet/minecraft/class_5250;")));
        transformer.clearRedirectsForTesting();
        for (String target : List.of("1.20.4", "1.21.6", "26.3")) {
            Pre1_20_5ComponentJsonBridge.registerForTarget(transformer, target);
            assertTrue(transformer.getMethodRedirects().isEmpty(), target);
        }
    }

    // ---- every path, on the names old jars carry ----

    @ParameterizedTest(name = "{0}")
    @CsvSource({"game", "CLI transform", "CLI batch", "AOT"})
    @DisplayName("On NeoForge 1.21.1, a Forge 1.20.1 jar's SRG calls reach its own copy of the stand-in on every path")
    void forgeJarOnEveryPath(String path, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("texts.jar");
        Files.write(input, jar(Map.of("META-INF/mods.toml", forgeToml(), "mod/Srg.class", srgCalls())));
        onHost("neoforge", "1.21.1");
        Path output = dir.resolve("out.jar");
        switch (path) {
            case "game" -> {
                for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
                    if (Set.of("neoforge", "forge", "common").contains(shim.getModLoaderType())
                            && ShimRegistry.isAvailableOnHost(shim, "1.21.1")) {
                        ShimRegistration.register(shim, transformer);
                    }
                }
                com.retromod.mapping.SrgToMojangMapper.getInstance().applyTo(transformer);
                output = new ForgeModTransformer("1.21.1").transformMod(input, Files.createDirectories(dir.resolve("game")));
            }
            case "CLI transform" -> CliHarness.transform(input, output, "1.21.1");
            case "CLI batch" -> {
                Path in = Files.createDirectories(dir.resolve("batch-in"));
                Path out = Files.createDirectories(dir.resolve("batch-out"));
                Files.copy(input, in.resolve(input.getFileName()));
                CliHarness.batch(in, out, "1.21.1");
                try (var jars = Files.list(out)) {
                    output = jars.filter(file -> file.toString().endsWith(".jar")).findFirst().orElseThrow();
                }
            }
            default -> CliHarness.aot(input, output, "1.21.1");
        }
        try (JarFile jar = new JarFile(output.toFile())) {
            List<String> calls = BridgeHarness.calls(jar.getInputStream(jar.getJarEntry("mod/Srg.class")).readAllBytes(), "read");
            String fromJson = JSON + ".fromJson(L" + GSON_ELEMENT + ";)L" + MUTABLE + ";";
            assertTrue(calls.size() == 1 && calls.get(0).startsWith(SyntheticEmbedder.PREFIX) && calls.get(0).endsWith(fromJson),
                    path + ": the call reaches the jar's own stand-in: " + calls);
            String root = calls.get(0).substring(0, calls.get(0).length() - fromJson.length());
            assertNotNull(jar.getJarEntry(root + JSON + ".class"), path);
            assertNotNull(jar.getJarEntry(root + LegacyEnchantmentRegistryShim.HELPER + ".class"),
                    path + ": the jar carries the view the stand-in reads registries through");
        }
    }

    // ---- the Fabric bridge at run time ----

    @Test
    @DisplayName("On Fabric 1.21.x the old call passes the loaded world's registries, and the empty ones before any world")
    void fabricBridgePassesTheWorld() throws Throwable {
        Map<String, byte[]> host = BridgeHarness.compile(tempDir.resolve("host"), INTERMEDIARY_HOST);
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        Map<String, byte[]> mod = BridgeHarness.transform("1.21.1",
                t -> Pre1_20_5ComponentJsonBridge.registerForTarget(t, "1.21.1"), Map.of("mod/Powers", intermediaryCall()));
        BridgeHarness.Run run = new BridgeHarness.Run(host, mod);
        assertEquals("text via empty", String.valueOf(run.call("mod/Powers", "read", "text")),
                "before any world, the empty registries, as before");
        run.call("test/World", "start");
        assertEquals("text via world", String.valueOf(run.call("mod/Powers", "read", "text")),
                "with a server running, its registries, so items and entities on hover resolve");
    }

    // ---- the stand-ins at run time ----

    @Test
    @DisplayName("The style adapter reads only the style keys, so a mod's own keys and siblings beside them do not fail it")
    void styleAdapterReadsOnlyStyleKeys() throws Throwable {
        BridgeHarness.Run run = standIns();
        Object adapter = run.loadClass(STYLE_JSON.replace('/', '.')).getConstructor().newInstance();
        // The 1.20.1 text adapter handed the whole object to the style adapter, as CreativeCore's does.
        com.google.gson.JsonElement text = com.google.gson.JsonParser.parseString(
                "{\"translate\":\"k\",\"color\":\"red\",\"extra\":[{\"creativecore_item\":{\"id\":\"x\"}}]}");
        Object style = adapter.getClass().getMethod("deserialize", com.google.gson.JsonElement.class, java.lang.reflect.Type.class,
                com.google.gson.JsonDeserializationContext.class).invoke(adapter, text, null, null);
        assertEquals("style red", String.valueOf(style));
    }

    @Test
    @DisplayName("A number reads as its text, as before 1.20.5, and plain text writes as {\"text\": ...}, as before 1.20.3")
    void numbersAndPlainText() throws Throwable {
        BridgeHarness.Run run = standIns();
        java.lang.reflect.Method fromJson = run.loadClass(JSON.replace('/', '.')).getMethod("fromJson", String.class);
        assertEquals("text 5", String.valueOf(fromJson.invoke(null, "5")));
        Object fromObject = fromJson.invoke(null, "{\"text\":5}");
        assertEquals("text 5", String.valueOf(fromObject));
        Object plain = fromJson.invoke(null, "\"plain\"");
        Class<?> component = run.loadClass("net.minecraft.network.chat.Component");
        assertEquals("{\"text\":\"plain\"}", run.loadClass(JSON.replace('/', '.')).getMethod("toJson", component).invoke(null, plain));
    }

    @Test
    @DisplayName("Only text with an old click or hover event, or a style flag written as a string, goes through the host's fixer")
    void fixerRunsOnlyWhenTheTextNeedsIt() throws Throwable {
        BridgeHarness.Run run = standIns();
        java.lang.reflect.Method fromJson = run.loadClass(JSON.replace('/', '.')).getMethod("fromJson", String.class);
        java.lang.reflect.Field calls = run.loadClass("net.minecraft.util.datafix.DataFixers").getField("calls");
        // Plain text and text in today's shape read straight through the codec, as fast as the host reads them.
        for (String text : List.of("\"plain\"", "{\"text\":\"a\",\"color\":\"red\"}", "{\"text\":\"a\",\"extra\":[\"b\"]}")) {
            fromJson.invoke(null, text);
        }
        assertEquals(0, calls.getInt(null), "text the fixer would not change skips it");
        // This fixer changes nothing, so the small codec then rejects the old keys; the count is what matters here.
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> fromJson.invoke(null, "{\"text\":\"a\",\"extra\":[{\"text\":\"b\",\"clickEvent\":{}}]}"));
        assertEquals(1, calls.getInt(null), "an old click event, even in a sibling, is upgraded");
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> fromJson.invoke(null, "{\"text\":\"a\",\"bold\":\"true\"}"));
        assertEquals(2, calls.getInt(null), "a style flag written as a string is upgraded");
    }

    @Test
    @DisplayName("Before 1.21.5, which has no text fixer, a style flag written as a string reads as Boolean.parseBoolean did")
    void stringFlagsWithoutTheFixer() throws Throwable {
        Map<String, String> host = new LinkedHashMap<>(CODEC_HOST);
        host.put("net/minecraft/util/datafix/fixes/References.java", NO_TEXT_FIXER);
        LegacyComponentJsonBridge.register(transformer, Serializer.TAKES_REGISTRIES, false);
        BridgeHarness.Run run = new BridgeHarness.Run(BridgeHarness.compile(tempDir.resolve("no-fixer"), host), Map.of());
        java.lang.reflect.Method fromJson = run.loadClass(JSON.replace('/', '.')).getMethod("fromJson", String.class);
        assertEquals("text a bold", String.valueOf(fromJson.invoke(null, "{\"text\":\"a\",\"bold\":\"TRUE\"}")));
        assertEquals("text a", String.valueOf(fromJson.invoke(null, "{\"text\":\"a\",\"bold\":\"no\"}")));
        assertEquals("text a", String.valueOf(fromJson.invoke(null,
                "{\"text\":\"a\",\"extra\":[{\"text\":\"b\",\"italic\":\"false\"}]}")), "a sibling's flag too");
        Object adapter = run.loadClass(STYLE_JSON.replace('/', '.')).getConstructor().newInstance();
        Object style = adapter.getClass().getMethod("deserialize", com.google.gson.JsonElement.class, java.lang.reflect.Type.class,
                com.google.gson.JsonDeserializationContext.class).invoke(adapter,
                com.google.gson.JsonParser.parseString("{\"color\":\"red\",\"bold\":\"true\"}"), null, null);
        assertEquals("style red", String.valueOf(style), "the style adapter reads the same way");
        assertEquals(0, run.loadClass("net.minecraft.util.datafix.DataFixers").getField("calls").getInt(null));
    }

    /** The two stand-ins loaded beside a small host whose text codec, like the real one, rejects keys it does not know. */
    private BridgeHarness.Run standIns() throws Exception {
        LegacyComponentJsonBridge.register(transformer, Serializer.GONE, false);
        return new BridgeHarness.Run(BridgeHarness.compile(tempDir.resolve("codec-host"), CODEC_HOST), Map.of());
    }

    // References before 1.21.5: no text component type, so no text fixer.
    static final String NO_TEXT_FIXER = """
            package net.minecraft.util.datafix.fixes;
            public class References {}""";

    // ---- helpers ----

    private void onHost(String loader, String host) {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        McReflect.setForceForge(loader.equals("forge"));
    }

    /** The old mod compiled against 1.20.1 shapes, then transformed as a mod built for {@code range}. */
    private Map<String, byte[]> transformOld(String range) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>(OLD_HOST);
        sources.put("mod/Texts.java", OLD_MOD);
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(tempDir.resolve("old"), sources), "mod/");
        Map<String, byte[]> out = new LinkedHashMap<>();
        transformer.setModSourceVersion(range);
        mod.forEach((name, bytes) -> out.put(name, transformer.transformClass(bytes, name)));
        return out;
    }

    private static List<String> news(byte[] bytes, String method) {
        List<String> types = new ArrayList<>();
        for (AbstractInsnNode insn : BridgeHarness.method(bytes, method).instructions) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == NEW) types.add(type.desc);
        }
        return types;
    }

    private static String superName(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.superName;
    }

    private static ClassNode node(String name, MethodNode method) {
        ClassNode node = new ClassNode();
        node.name = name;
        if (method != null) node.methods.add(method);
        return node;
    }

    /** {@code read()} calls 1.20.5's {@code fromJson(String, HolderLookup.Provider)}, as a mod built for it does. */
    private static byte[] providerCall() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/New", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESTATIC, SERIALIZER, "fromJson", "(Ljava/lang/String;L" + PROVIDER + ";)L" + MUTABLE + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code read(json)} calls 1.20.1's {@code fromJson(JsonElement)} under its SRG id, as Saint's Dragons does. */
    private static byte[] srgCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Srg", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "(L" + GSON_ELEMENT + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, SERIALIZER, "m_130691_", "(L" + GSON_ELEMENT + ";)L" + MUTABLE + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code read(json)} calls the intermediary {@code fromJson(String)} a Fabric 1.20.1 mod carries. */
    private static byte[] intermediaryCall() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Powers", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "(Ljava/lang/String;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/class_2561$class_2562", "method_10877",
                "(Ljava/lang/String;)Lnet/minecraft/class_5250;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] forgeToml() {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"texts\"\n"
                + "version=\"1.0\"\n[[dependencies.texts]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(entries).entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }

    // 1.20.1's serializers: Gson type adapters with the statics that took no registries.
    private static final Map<String, String> OLD_HOST = Map.of(
            "net/minecraft/network/chat/Component.java", """
                    package net.minecraft.network.chat;
                    public interface Component {
                        class Serializer implements com.google.gson.JsonDeserializer<MutableComponent>, com.google.gson.JsonSerializer<Component> {
                            public MutableComponent deserialize(com.google.gson.JsonElement json, java.lang.reflect.Type type,
                                    com.google.gson.JsonDeserializationContext context) { return null; }
                            public com.google.gson.JsonElement serialize(Component component, java.lang.reflect.Type type,
                                    com.google.gson.JsonSerializationContext context) { return null; }
                            public static MutableComponent fromJson(String json) { return null; }
                            public static MutableComponent fromJson(com.google.gson.JsonElement json) { return null; }
                            public static MutableComponent fromJsonLenient(String json) { return null; }
                            public static String toJson(Component component) { return null; }
                            public static com.google.gson.JsonElement toJsonTree(Component component) { return null; }
                            public static String toStableJson(Component component) { return null; }
                        }
                    }""",
            "net/minecraft/network/chat/MutableComponent.java", """
                    package net.minecraft.network.chat;
                    public class MutableComponent implements Component {}""",
            "net/minecraft/network/chat/Style.java", """
                    package net.minecraft.network.chat;
                    public class Style {
                        public static class Serializer implements com.google.gson.JsonDeserializer<Style>, com.google.gson.JsonSerializer<Style> {
                            public Style deserialize(com.google.gson.JsonElement json, java.lang.reflect.Type type,
                                    com.google.gson.JsonDeserializationContext context) { return null; }
                            public com.google.gson.JsonElement serialize(Style style, java.lang.reflect.Type type,
                                    com.google.gson.JsonSerializationContext context) { return null; }
                        }
                    }""");

    private static final String OLD_MOD = """
            package mod;
            import com.google.gson.*;
            import net.minecraft.network.chat.*;
            public class Texts {
                // Built once, as YACL and Sounds build theirs.
                static final Gson GSON = new GsonBuilder()
                        .registerTypeHierarchyAdapter(Component.class, new Component.Serializer())
                        .registerTypeHierarchyAdapter(Style.class, new Style.Serializer()).create();
                public static void calls(JsonElement tree, Component text) {
                    Component.Serializer.fromJson("{}");
                    Component.Serializer.fromJson(tree);
                    Component.Serializer.fromJsonLenient("{}");
                    Component.Serializer.toJson(text);
                    Component.Serializer.toJsonTree(text);
                    Component.Serializer.toStableJson(text);
                }
                // CreativeCore extends the serializer with its own text kinds.
                public static class Custom extends Component.Serializer {}
            }
            """;

    // A host text codec that reads text, translate, color and extra, and fails on any other key, as the real one does.
    static final Map<String, String> CODEC_HOST = Map.ofEntries(
            Map.entry("com/mojang/serialization/DynamicOps.java", "package com.mojang.serialization; public interface DynamicOps<T> {}"),
            Map.entry("com/mojang/serialization/JsonOps.java", """
                    package com.mojang.serialization;
                    public final class JsonOps implements DynamicOps<com.google.gson.JsonElement> {
                        public static final JsonOps INSTANCE = new JsonOps();
                    }"""),
            Map.entry("com/mojang/serialization/MapCodec.java", "package com.mojang.serialization; public interface MapCodec<A> {}"),
            Map.entry("com/mojang/serialization/DataResult.java", """
                    package com.mojang.serialization;
                    public interface DataResult<R> {
                        static <R> DataResult<R> success(R value) { return new Result<>(value, null); }
                        static <R> DataResult<R> error(String error) { return new Result<>(null, error); }
                        <E extends Throwable> R getOrThrow(java.util.function.Function<String, E> failure) throws E;
                        record Result<R>(R value, String error) implements DataResult<R> {
                            public <E extends Throwable> R getOrThrow(java.util.function.Function<String, E> failure) throws E {
                                if (error != null) throw failure.apply(error);
                                return value;
                            }
                        }
                    }"""),
            Map.entry("com/mojang/serialization/Codec.java", """
                    package com.mojang.serialization;
                    public interface Codec<A> {
                        <T> DataResult<A> parse(DynamicOps<T> ops, T input);
                        <T> DataResult<T> encodeStart(DynamicOps<T> ops, A input);
                    }"""),
            Map.entry("net/minecraft/resources/RegistryOps.java", """
                    package net.minecraft.resources;
                    public final class RegistryOps<T> implements com.mojang.serialization.DynamicOps<T> {}"""),
            Map.entry("net/minecraft/core/HolderLookup.java", """
                    package net.minecraft.core;
                    public interface HolderLookup {
                        interface Provider {
                            <V> net.minecraft.resources.RegistryOps<V> createSerializationContext(com.mojang.serialization.DynamicOps<V> ops);
                        }
                    }"""),
            Map.entry("net/minecraft/network/chat/Component.java", """
                    package net.minecraft.network.chat;
                    public interface Component {
                        static MutableComponent empty() { return new MutableComponent("", new Style(null)); }
                        Style getStyle();
                        default MutableComponent copy() { return (MutableComponent) this; }
                    }"""),
            Map.entry("net/minecraft/network/chat/MutableComponent.java", """
                    package net.minecraft.network.chat;
                    public class MutableComponent implements Component {
                        final String text; final Style style;
                        public MutableComponent(String text, Style style) { this.text = text; this.style = style; }
                        public MutableComponent withStyle(Style style) { return new MutableComponent(text, style); }
                        public Style getStyle() { return style; }
                        public String toString() { return "text " + text; }
                    }"""),
            Map.entry("net/minecraft/network/chat/Style.java", """
                    package net.minecraft.network.chat;
                    public class Style {
                        final String color;
                        public Style(String color) { this.color = color; }
                        public boolean isEmpty() { return color == null; }
                        public String toString() { return "style " + color; }
                        public static class Serializer {
                            public static final com.mojang.serialization.MapCodec<Style> MAP_CODEC = null;
                            public static final com.mojang.serialization.Codec<Style> CODEC = null;
                        }
                    }"""),
            Map.entry("net/minecraft/network/chat/ComponentSerialization.java", """
                    package net.minecraft.network.chat;
                    import com.google.gson.*;
                    import com.mojang.serialization.*;
                    public final class ComponentSerialization {
                        public static final Codec<Component> CODEC = new Codec<>() {
                            public <T> DataResult<Component> parse(DynamicOps<T> ops, T input) {
                                try {
                                    return DataResult.success(read((JsonElement) input));
                                } catch (RuntimeException failure) {
                                    return DataResult.error(failure.getMessage());
                                }
                            }
                            @SuppressWarnings("unchecked")
                            public <T> DataResult<T> encodeStart(DynamicOps<T> ops, Component input) {
                                MutableComponent text = (MutableComponent) input;
                                if (text.style.isEmpty()) return DataResult.success((T) new JsonPrimitive(text.text));
                                JsonObject json = new JsonObject();
                                json.addProperty("text", text.text);
                                json.addProperty("color", text.style.color);
                                return DataResult.success((T) json);
                            }
                        };
                        static MutableComponent read(JsonElement json) {
                            if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) return new MutableComponent(json.getAsString(), new Style(null));
                            if (!json.isJsonObject()) throw new IllegalStateException("Not a string: " + json);
                            JsonObject object = json.getAsJsonObject();
                            for (String key : object.keySet()) {
                                if (!java.util.Set.of("text", "translate", "color", "extra", "bold", "italic").contains(key)) throw new IllegalStateException("No matching codec found: " + key);
                            }
                            // Style flags are booleans, as in the real codec; a flag written as a string fails.
                            for (String flag : java.util.List.of("bold", "italic")) {
                                if (object.has(flag) && !object.get(flag).getAsJsonPrimitive().isBoolean()) throw new IllegalStateException("Not a boolean: " + object.get(flag));
                            }
                            if (object.has("extra")) for (JsonElement sibling : object.getAsJsonArray("extra")) read(sibling);
                            JsonElement text = object.has("text") ? object.get("text") : object.get("translate");
                            if (text == null || !text.getAsJsonPrimitive().isString()) throw new IllegalStateException("Not a string: " + text);
                            String bold = object.has("bold") && object.get("bold").getAsBoolean() ? " bold" : "";
                            return new MutableComponent(text.getAsString() + bold, new Style(object.has("color") ? object.get("color").getAsString() : null));
                        }
                    }"""),
            Map.entry("net/fabricmc/loader/api/FabricLoader.java", """
                    package net.fabricmc.loader.api;
                    public final class FabricLoader {
                        private static final FabricLoader INSTANCE = new FabricLoader();
                        public static FabricLoader getInstance() { return INSTANCE; }
                        public Object getGameInstance() { return null; }
                        public Object getEnvironmentType() { return "SERVER"; }
                    }"""),
            // A host from 1.21.5, with the text component type and a fixer that counts its runs and changes nothing.
            Map.entry("com/mojang/datafixers/DSL.java", "package com.mojang.datafixers; public interface DSL { interface TypeReference {} }"),
            Map.entry("com/mojang/serialization/Dynamic.java", """
                    package com.mojang.serialization;
                    public class Dynamic<T> {
                        private final T value;
                        public Dynamic(DynamicOps<T> ops, T value) { this.value = value; }
                        public T getValue() { return value; }
                    }"""),
            Map.entry("com/mojang/datafixers/DataFixer.java", """
                    package com.mojang.datafixers;
                    public interface DataFixer {
                        <T> com.mojang.serialization.Dynamic<T> update(DSL.TypeReference type, com.mojang.serialization.Dynamic<T> input,
                                int version, int newVersion);
                    }"""),
            Map.entry("net/minecraft/util/datafix/DataFixers.java", """
                    package net.minecraft.util.datafix;
                    public class DataFixers {
                        public static int calls;
                        public static com.mojang.datafixers.DataFixer getDataFixer() {
                            return new com.mojang.datafixers.DataFixer() {
                                public <T> com.mojang.serialization.Dynamic<T> update(com.mojang.datafixers.DSL.TypeReference type,
                                        com.mojang.serialization.Dynamic<T> input, int version, int newVersion) {
                                    calls++;
                                    return input;
                                }
                            };
                        }
                    }"""),
            Map.entry("net/minecraft/util/datafix/fixes/References.java", """
                    package net.minecraft.util.datafix.fixes;
                    public class References {
                        public static final com.mojang.datafixers.DSL.TypeReference TEXT_COMPONENT = new com.mojang.datafixers.DSL.TypeReference() {};
                    }"""),
            Map.entry("net/minecraft/SharedConstants.java", """
                    package net.minecraft;
                    public class SharedConstants { public static final int WORLD_VERSION = 4440; }"""));

    // A Fabric 1.21.x host in intermediary names: the registry-taking fromJson records which registries it got.
    private static final Map<String, String> INTERMEDIARY_HOST = Map.of(
            "net/minecraft/class_2378.java", "package net.minecraft; public interface class_2378 {}",
            "net/minecraft/class_7225.java", "package net.minecraft; public interface class_7225 { interface class_7874 {} }",
            "net/minecraft/class_5455.java", """
                    package net.minecraft;
                    public interface class_5455 extends class_7225.class_7874 {
                        class_6890 field_40585 = new class_6890() { public String toString() { return "empty"; } };
                        interface class_6890 extends class_5455 {}
                    }""",
            "net/minecraft/class_2561.java", "package net.minecraft; public interface class_2561 { class class_2562 {"
                    + " public static class_5250 method_10877(String json, class_7225.class_7874 registries) {"
                    + " return new class_5250(json + \" via \" + registries); } } }",
            "net/minecraft/class_5250.java", """
                    package net.minecraft;
                    public class class_5250 implements class_2561 {
                        private final String text;
                        public class_5250(String text) { this.text = text; }
                        public String toString() { return text; }
                    }""",
            "net/minecraft/server/MinecraftServer.java", """
                    package net.minecraft.server;
                    public class MinecraftServer {
                        public net.minecraft.class_5455 method_30611() { return new net.minecraft.class_5455.class_6890() {
                            public String toString() { return "world"; } }; }
                    }""",
            "net/fabricmc/loader/api/FabricLoader.java", """
                    package net.fabricmc.loader.api;
                    public final class FabricLoader {
                        public static Object game;
                        private static final FabricLoader INSTANCE = new FabricLoader();
                        public static FabricLoader getInstance() { return INSTANCE; }
                        public Object getGameInstance() { return game; }
                        public Object getEnvironmentType() { return "SERVER"; }
                    }""",
            "test/World.java", """
                    package test;
                    public final class World {
                        public static void start() { net.fabricmc.loader.api.FabricLoader.game = new net.minecraft.server.MinecraftServer(); }
                    }""");
}
