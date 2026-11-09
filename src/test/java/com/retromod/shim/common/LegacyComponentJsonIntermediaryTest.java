/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyComponentJsonBridge.Serializer;
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
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old Fabric text code on 1.20.3 to 1.21.11 hosts, which run intermediary names, reaches the intermediary copies of the stand-ins. */
class LegacyComponentJsonIntermediaryTest {

    private static final String SERIALIZER = "net/minecraft/class_2561$class_2562";
    private static final String STYLE_SERIALIZER = "net/minecraft/class_2583$class_2584";
    private static final String JSON = LegacyComponentJsonBridge.INTERMEDIARY_JSON;
    private static final String STYLE_JSON = LegacyComponentJsonBridge.INTERMEDIARY_STYLE;
    private static final String COMPONENT = "net/minecraft/class_2561";
    private static final String MUTABLE = "net/minecraft/class_5250";
    private static final String PROVIDER = "net/minecraft/class_7225$class_7874";
    private static final String GSON_ELEMENT = "com/google/gson/JsonElement";
    private static final String OLD_RANGE = "[1.20.1,1.20.2)";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path tempDir;

    @BeforeEach
    void clear() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    // ---- host shapes and gates ----

    @Test
    @DisplayName("A Fabric host's intermediary classes decide the shape: the old adapter, the old statics, registry-taking statics, or none")
    void intermediaryHostClassesDecide() {
        ClassNode serialization = node("net/minecraft/class_8824", null);
        ClassNode adapter = node(SERIALIZER, null);
        adapter.interfaces.add("com/google/gson/JsonDeserializer");
        ClassNode statics = node(SERIALIZER, new MethodNode(ACC_PUBLIC | ACC_STATIC, "method_10877",
                "(Ljava/lang/String;)L" + MUTABLE + ";", null, null));
        ClassNode registries = node(SERIALIZER, new MethodNode(ACC_PUBLIC | ACC_STATIC, "method_10877",
                "(Ljava/lang/String;L" + PROVIDER + ";)L" + MUTABLE + ";", null, null));
        assertEquals(Serializer.OLD, shape(Map.of(SERIALIZER, adapter), "1.20.2"), "1.20.2 has no ComponentSerialization");
        assertEquals(Serializer.OLD, shape(Map.of("net/minecraft/class_8824", serialization, SERIALIZER, adapter), "1.20.4"));
        assertEquals(Serializer.STATICS, shape(Map.of("net/minecraft/class_8824", serialization, SERIALIZER, statics), "1.20.4"));
        assertEquals(Serializer.TAKES_REGISTRIES,
                shape(Map.of("net/minecraft/class_8824", serialization, SERIALIZER, registries), "1.21.1"));
        assertEquals(Serializer.GONE, shape(Map.of("net/minecraft/class_8824", serialization), "1.21.11"));

        ClassNode styleAdapter = node(STYLE_SERIALIZER, null);
        styleAdapter.interfaces.add("com/google/gson/JsonDeserializer");
        assertTrue(LegacyComponentJsonBridge.styleIsAdapter(
                Map.of("net/minecraft/class_8824", serialization, STYLE_SERIALIZER, styleAdapter)::get, "1.21.1",
                LegacyComponentJsonBridge.INTERMEDIARY));
        assertFalse(LegacyComponentJsonBridge.styleIsAdapter(
                Map.of("net/minecraft/class_8824", serialization, STYLE_SERIALIZER, node(STYLE_SERIALIZER, null))::get,
                "1.21.1", LegacyComponentJsonBridge.INTERMEDIARY));
    }

    @ParameterizedTest(name = "target {0}")
    @CsvSource({"1.20.1, none, none", "1.20.2, none, none", "1.20.3, 1.20.3, 1.20.3", "1.20.4, 1.20.3, 1.20.3",
            "1.20.5, 1.20.5, 1.20.3", "1.21.1, 1.20.5, 1.20.3", "1.21.5, 1.20.5, 1.20.3", "1.21.6, all, 1.20.3",
            "1.21.11, all, 1.20.3", "26.1, none, none", "26.3, none, none"})
    @DisplayName("Offline, the adapters go to the copies from 1.20.3 and the serializer from 1.21.6, for mods built before each change")
    void offlineGates(String target, String component, String style) {
        LegacyComponentJsonBridge.registerIntermediaryForTarget(transformer, target);
        assertEquals(expected(component, OLD_RANGE, JSON, SERIALIZER), news(OLD_RANGE).get(0), target + ": a 1.20.1 mod's text adapter");
        assertEquals(expected(style, OLD_RANGE, STYLE_JSON, STYLE_SERIALIZER), news(OLD_RANGE).get(1), target + ": its style adapter");
        // A mod built at the change keeps the host's class while the host has it.
        for (String built : List.of("[1.20.3,1.20.4)", "[1.21.1,1.21.2)")) {
            assertEquals(expected(component, built, JSON, SERIALIZER), news(built).get(0), target + ": a mod built for " + built);
            assertEquals(expected(style, built, STYLE_JSON, STYLE_SERIALIZER), news(built).get(1), target + ": " + built);
        }
    }

    // ---- transform ----

    @ParameterizedTest(name = "on {0}")
    @CsvSource({"1.20.4", "1.21.1", "1.21.11"})
    @DisplayName("Every old member a Fabric 1.20.1 mod calls goes to the copies: the statics, the Gson adapters, and a subclass")
    void oldModReachesTheCopies(String host) throws Exception {
        LegacyComponentJsonBridge.registerIntermediaryForTarget(transformer, host);
        Map<String, byte[]> out = transformOld();

        assertEquals(List.of(JSON + ".method_10877(Ljava/lang/String;)L" + MUTABLE + ";",
                JSON + ".method_10872(L" + GSON_ELEMENT + ";)L" + MUTABLE + ";",
                JSON + ".method_10873(Ljava/lang/String;)L" + MUTABLE + ";",
                JSON + ".method_10867(L" + COMPONENT + ";)Ljava/lang/String;",
                JSON + ".method_10868(L" + COMPONENT + ";)L" + GSON_ELEMENT + ";",
                JSON + ".method_43858(L" + COMPONENT + ";)Ljava/lang/String;"), BridgeHarness.calls(out.get("mod/Texts"), "calls"));
        assertEquals(List.of(JSON, STYLE_JSON), typesBuilt(out.get("mod/Texts")), "the two Gson adapters are the copies");
        // CreativeCore's pattern: its adapter extends the serializer and calls the typed read it overrides.
        assertEquals(JSON, superName(out.get("mod/Texts$Custom")));
        assertTrue(BridgeHarness.calls(out.get("mod/Texts$Custom"), "method_10871").contains(JSON + ".method_10871(L" + GSON_ELEMENT
                + ";Ljava/lang/reflect/Type;Lcom/google/gson/JsonDeserializationContext;)L" + MUTABLE + ";"));
        Map<String, byte[]> synthetics = transformer.getSyntheticClasses();
        assertTrue(synthetics.containsKey(JSON) && synthetics.containsKey(STYLE_JSON) && synthetics.containsKey(LegacyComponentJsonBridge.NO_CONTEXT));
        assertFalse(synthetics.keySet().stream().anyMatch(name -> name.contains("LegacyRegistryContext") || name.contains("/forge/")),
                "a Fabric jar carries none of the Forge-side context: " + synthetics.keySet());
    }

    @Test
    @DisplayName("The copies name only intermediary Minecraft classes, keep Gson's bridge names, and read the fixer's fields by their ids")
    void copiesUseIntermediaryNames() {
        LegacyComponentJsonBridge.registerIntermediaryForTarget(transformer, "1.21.11");
        for (String name : List.of(JSON, STYLE_JSON, LegacyComponentJsonBridge.NO_CONTEXT)) {
            ClassNode node = new ClassNode();
            new ClassReader(transformer.getSyntheticClasses().get(name)).accept(node, 0);
            String text = dump(node);
            for (String mojang : List.of("net/minecraft/network/", "net/minecraft/core/", "net/minecraft/util/",
                    "net/minecraft/resources/", "net/minecraft/SharedConstants", "LegacyRegistryContext")) {
                assertFalse(text.contains(mojang), name + " still names " + mojang);
            }
        }
        List<String> methods = methodNames(JSON);
        assertTrue(methods.containsAll(List.of("deserialize", "serialize", "method_10871", "method_10874")), methods.toString());
        assertTrue(methodNames(STYLE_JSON).containsAll(List.of("deserialize", "serialize", "method_10991", "method_10990")));
        List<Object> constants = new ArrayList<>();
        ClassNode json = new ClassNode();
        new ClassReader(transformer.getSyntheticClasses().get(JSON)).accept(json, 0);
        for (MethodNode method : json.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) constants.add(ldc.cst);
            }
        }
        assertTrue(constants.containsAll(List.of("field_29732", "field_55639")), "the reflected fields take their ids");
        assertFalse(constants.contains("WORLD_VERSION") || constants.contains("TEXT_COMPONENT"));
    }

    // ---- the copies at run time ----

    @Test
    @DisplayName("On an intermediary 1.21.11 host the copies read numbers and old keys, write the old shape, and adapt text and style for Gson")
    void copiesRunOnAnIntermediaryHost() throws Throwable {
        LegacyComponentJsonBridge.registerIntermediaryForTarget(transformer, "1.21.11");
        BridgeHarness.Run run = new BridgeHarness.Run(BridgeHarness.compile(tempDir.resolve("host"), codecHost(true, true)), Map.of());
        assertEquals("text 5", String.valueOf(run.call(JSON, "method_10877", "5")));
        Class<?> component = run.loadClass("net.minecraft.class_2561");
        Object plain = run.call(JSON, "method_10877", "\"plain\"");
        assertEquals("{\"text\":\"plain\"}", run.loadClass(JSON.replace('/', '.')).getMethod("method_10867", component).invoke(null, plain));

        // The Gson adapters, as a 1.20.1 mod registers them.
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                .registerTypeHierarchyAdapter(component, run.loadClass(JSON.replace('/', '.')).getConstructor().newInstance())
                .registerTypeHierarchyAdapter(run.loadClass("net.minecraft.class_2583"),
                        run.loadClass(STYLE_JSON.replace('/', '.')).getConstructor().newInstance()).create();
        assertEquals("text a", String.valueOf(gson.fromJson("{\"text\":\"a\",\"color\":\"red\"}", component)));
        assertEquals("style red", String.valueOf(gson.fromJson(
                "{\"translate\":\"k\",\"color\":\"red\",\"extra\":[{\"modkey\":1}]}", run.loadClass("net.minecraft.class_2583"))));

        // The host's fixer runs, through the ids the copy reads by reflection, only for text that needs it.
        java.lang.reflect.Field calls = run.loadClass("net.minecraft.class_3551").getField("calls");
        run.call(JSON, "method_10877", "{\"text\":\"a\",\"color\":\"red\"}");
        assertEquals(0, calls.getInt(null));
        // This fixer changes nothing, so the small codec then rejects the old key; the count is what matters here.
        assertThrows(com.google.gson.JsonParseException.class,
                () -> run.call(JSON, "method_10877", "{\"text\":\"a\",\"clickEvent\":{}}"));
        assertEquals(1, calls.getInt(null), "an old click event goes through the host's fixer");
    }

    @ParameterizedTest(name = "the host has registry ops: {0}")
    @CsvSource({"true, net.minecraft.class_6903", "false, com.mojang.serialization.JsonOps"})
    @DisplayName("With a world loaded the copies read with its registries, and on 1.20.3 and 1.20.4, which have no registry ops, without")
    void worldRegistries(boolean registryOps, String expectedOps) throws Throwable {
        LegacyComponentJsonBridge.registerIntermediaryForTarget(transformer, registryOps ? "1.21.11" : "1.20.4");
        BridgeHarness.Run run = new BridgeHarness.Run(BridgeHarness.compile(tempDir.resolve("host"), codecHost(registryOps, true)), Map.of());
        java.lang.reflect.Field seen = run.loadClass("net.minecraft.class_8824").getField("opsSeen");
        run.call(JSON, "method_10877", "\"before\"");
        assertEquals("com.mojang.serialization.JsonOps", seen.get(null), "before any world there are no registries");
        run.call("test/World", "start");
        for (String text : List.of("\"first\"", "\"second\"")) {
            assertEquals("text " + text.replace("\"", ""), String.valueOf(run.call(JSON, "method_10877", text)));
            assertEquals(expectedOps, seen.get(null));
        }
    }

    @ParameterizedTest(name = "the host has the text fixer: {0}")
    @CsvSource({"false", "true"})
    @DisplayName("Before 1.21.5 the copies read a style flag written as a string themselves; from 1.21.5 the host's fixer does")
    void stringFlags(boolean fixer) throws Throwable {
        LegacyComponentJsonBridge.registerIntermediaryForTarget(transformer, fixer ? "1.21.11" : "1.21.1");
        BridgeHarness.Run run = new BridgeHarness.Run(BridgeHarness.compile(tempDir.resolve("host"), codecHost(true, fixer)), Map.of());
        String text = "{\"text\":\"a\",\"bold\":\"true\",\"extra\":[{\"text\":\"b\",\"italic\":\"false\"}]}";
        java.lang.reflect.Field calls = run.loadClass("net.minecraft.class_3551").getField("calls");
        if (fixer) {
            // This fixer changes nothing, so the small codec then rejects the string; the copy left it to the fixer.
            assertThrows(com.google.gson.JsonParseException.class, () -> run.call(JSON, "method_10877", text));
            assertEquals(1, calls.getInt(null));
        } else {
            assertEquals("text a bold", String.valueOf(run.call(JSON, "method_10877", text)));
            assertEquals(0, calls.getInt(null));
        }
    }

    @Test
    @DisplayName("On 1.20.5 to 1.21.5 the registry-passing helper stands aside where the copy registered, so old calls read as on the other loaders")
    void helperStandsAsideForTheCopy() throws Exception {
        com.retromod.core.RetromodTransformer.MethodKey fromJson = new com.retromod.core.RetromodTransformer.MethodKey(SERIALIZER,
                "method_10877", "(Ljava/lang/String;)L" + MUTABLE + ";");
        com.retromod.shim.fabric.Pre1_20_5ComponentJsonBridge.registerForTarget(transformer, "1.21.1");
        assertNotNull(transformer.getMethodRedirects().get(fromJson), "alone, the helper still answers");

        transformer.clearRedirectsForTesting();
        LegacyComponentJsonBridge.registerIntermediaryForTarget(transformer, "1.21.1");
        com.retromod.shim.fabric.Pre1_20_5ComponentJsonBridge.registerForTarget(transformer, "1.21.1");
        assertNull(transformer.getMethodRedirects().get(fromJson));
        assertEquals(List.of(JSON + ".method_10877(Ljava/lang/String;)L" + MUTABLE + ";",
                JSON + ".method_10872(L" + GSON_ELEMENT + ";)L" + MUTABLE + ";",
                JSON + ".method_10873(Ljava/lang/String;)L" + MUTABLE + ";",
                JSON + ".method_10867(L" + COMPONENT + ";)Ljava/lang/String;",
                JSON + ".method_10868(L" + COMPONENT + ";)L" + GSON_ELEMENT + ";",
                JSON + ".method_43858(L" + COMPONENT + ";)Ljava/lang/String;"), BridgeHarness.calls(transformOld().get("mod/Texts"), "calls"));
    }

    // ---- helpers ----

    private Serializer shape(Map<String, ClassNode> host, String target) {
        return LegacyComponentJsonBridge.serializer(host::get, target, LegacyComponentJsonBridge.INTERMEDIARY);
    }

    /** The class a mod built for {@code range} constructs, given which mods the redirect is limited to. */
    private static String expected(String limit, String range, String standIn, String host) {
        if (limit.equals("none")) return host;
        if (limit.equals("all")) return standIn;
        String built = range.substring(1, range.indexOf(','));
        return RetromodVersion.compareMcVersions(built, limit) < 0 ? standIn : host;
    }

    /** The classes a mod built for {@code range} constructs for its two adapters. */
    private List<String> news(String range) {
        transformer.setModSourceVersion(range);
        return typesBuilt(transformer.transformClass(adapters(), "mod/Adapters"));
    }

    private static List<String> typesBuilt(byte[] bytes) {
        List<String> types = new ArrayList<>();
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof TypeInsnNode type && type.getOpcode() == NEW && !type.desc.startsWith("com/google/")) {
                    types.add(type.desc);
                }
            }
        }
        return types;
    }

    private List<String> methodNames(String name) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.getSyntheticClasses().get(name)).accept(node, 0);
        return node.methods.stream().map(m -> m.name).toList();
    }

    private static String dump(ClassNode node) {
        StringBuilder text = new StringBuilder(node.superName).append(node.interfaces).append(node.signature);
        node.fields.forEach(f -> text.append(f.desc).append(f.signature));
        for (MethodNode method : node.methods) {
            text.append(method.desc).append(method.signature);
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) text.append(call.owner).append(call.desc);
                if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field) text.append(field.owner).append(field.desc);
                if (insn instanceof TypeInsnNode type) text.append(type.desc);
                if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) text.append(ldc.cst);
            }
        }
        return text.toString();
    }

    private static ClassNode node(String name, MethodNode method) {
        ClassNode node = new ClassNode();
        node.name = name;
        if (method != null) node.methods.add(method);
        return node;
    }

    private static String superName(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.superName;
    }

    /** {@code new Component.Serializer()} and {@code new Style.Serializer()} in intermediary names. */
    private static byte[] adapters() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Adapters", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "make", "()V", null, null);
        mv.visitCode();
        for (String type : List.of(SERIALIZER, STYLE_SERIALIZER)) {
            mv.visitTypeInsn(NEW, type);
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, type, "<init>", "()V", false);
            mv.visitInsn(POP);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The old mod compiled against 1.20.1's intermediary serializers, then transformed as a 1.20.1 mod. */
    private Map<String, byte[]> transformOld() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>(OLD_HOST);
        sources.put("mod/Texts.java", OLD_MOD);
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(tempDir.resolve("old"), sources), "mod/");
        Map<String, byte[]> out = new LinkedHashMap<>();
        transformer.setModSourceVersion(OLD_RANGE);
        mod.forEach((name, bytes) -> out.put(name, transformer.transformClass(bytes, name)));
        return out;
    }

    // 1.20.1's serializers in intermediary names: Gson type adapters with the statics that took no registries.
    private static final Map<String, String> OLD_HOST = Map.of(
            "net/minecraft/class_2561.java", """
                    package net.minecraft;
                    public interface class_2561 {
                        class class_2562 implements com.google.gson.JsonDeserializer<class_5250>, com.google.gson.JsonSerializer<class_2561> {
                            public class_5250 method_10871(com.google.gson.JsonElement json, java.lang.reflect.Type type,
                                    com.google.gson.JsonDeserializationContext context) { return null; }
                            public com.google.gson.JsonElement method_10874(class_2561 text, java.lang.reflect.Type type,
                                    com.google.gson.JsonSerializationContext context) { return null; }
                            public class_5250 deserialize(com.google.gson.JsonElement json, java.lang.reflect.Type type,
                                    com.google.gson.JsonDeserializationContext context) { return method_10871(json, type, context); }
                            public com.google.gson.JsonElement serialize(class_2561 text, java.lang.reflect.Type type,
                                    com.google.gson.JsonSerializationContext context) { return method_10874(text, type, context); }
                            public static class_5250 method_10877(String json) { return null; }
                            public static class_5250 method_10872(com.google.gson.JsonElement json) { return null; }
                            public static class_5250 method_10873(String json) { return null; }
                            public static String method_10867(class_2561 text) { return null; }
                            public static com.google.gson.JsonElement method_10868(class_2561 text) { return null; }
                            public static String method_43858(class_2561 text) { return null; }
                        }
                    }""",
            "net/minecraft/class_5250.java", """
                    package net.minecraft;
                    public class class_5250 implements class_2561 {}""",
            "net/minecraft/class_2583.java", """
                    package net.minecraft;
                    public class class_2583 {
                        public static class class_2584 implements com.google.gson.JsonDeserializer<class_2583>, com.google.gson.JsonSerializer<class_2583> {
                            public class_2583 deserialize(com.google.gson.JsonElement json, java.lang.reflect.Type type,
                                    com.google.gson.JsonDeserializationContext context) { return null; }
                            public com.google.gson.JsonElement serialize(class_2583 style, java.lang.reflect.Type type,
                                    com.google.gson.JsonSerializationContext context) { return null; }
                        }
                    }""");

    private static final String OLD_MOD = """
            package mod;
            import com.google.gson.*;
            import net.minecraft.*;
            public class Texts {
                static final Gson GSON = new GsonBuilder()
                        .registerTypeHierarchyAdapter(class_2561.class, new class_2561.class_2562())
                        .registerTypeHierarchyAdapter(class_2583.class, new class_2583.class_2584()).create();
                public static void calls(JsonElement tree, class_2561 text) {
                    class_2561.class_2562.method_10877("{}");
                    class_2561.class_2562.method_10872(tree);
                    class_2561.class_2562.method_10873("{}");
                    class_2561.class_2562.method_10867(text);
                    class_2561.class_2562.method_10868(text);
                    class_2561.class_2562.method_43858(text);
                }
                public static class Custom extends class_2561.class_2562 {
                    @Override
                    public class_5250 method_10871(JsonElement json, java.lang.reflect.Type type, JsonDeserializationContext context) {
                        return super.method_10871(json, type, context);
                    }
                }
            }
            """;

    /** A small intermediary host whose text codec, like the real one, rejects keys it does not know, with or without 1.20.5's registry ops and 1.21.5's text fixer. */
    private static Map<String, String> codecHost(boolean registryOps, boolean fixer) {
        Map<String, String> host = new LinkedHashMap<>();
        LegacyComponentJsonBridgeTest.CODEC_HOST.forEach((path, source) -> {
            if (path.startsWith("com/mojang/")) host.put(path, source);
        });
        host.put("net/minecraft/class_6903.java", """
                package net.minecraft;
                public final class class_6903<T> implements com.mojang.serialization.DynamicOps<T> {}""");
        host.put("net/minecraft/class_7225.java", "package net.minecraft; public interface class_7225 { interface class_7874 {"
                + (registryOps ? " default <V> class_6903<V> method_57093(com.mojang.serialization.DynamicOps<V> ops) {"
                        + " return new class_6903<>(); }" : "") + " } }");
        host.put("net/minecraft/class_2378.java", "package net.minecraft; public interface class_2378 {}");
        host.put("net/minecraft/class_5455.java", "package net.minecraft; public interface class_5455 extends class_7225.class_7874 {}");
        host.put("net/minecraft/class_2561.java", """
                package net.minecraft;
                public interface class_2561 {
                    static class_5250 method_43473() { return new class_5250("", new class_2583(null)); }
                    class_2583 method_10866();
                    default class_5250 method_27661() { return (class_5250) this; }
                }""");
        host.put("net/minecraft/class_5250.java", """
                package net.minecraft;
                public class class_5250 implements class_2561 {
                    final String text; final class_2583 style;
                    public class_5250(String text, class_2583 style) { this.text = text; this.style = style; }
                    public class_5250 method_27696(class_2583 style) { return new class_5250(text, style); }
                    public class_2583 method_10866() { return style; }
                    public String toString() { return "text " + text; }
                }""");
        host.put("net/minecraft/class_2583.java", """
                package net.minecraft;
                public class class_2583 {
                    final String color;
                    public class_2583(String color) { this.color = color; }
                    public boolean method_10967() { return color == null; }
                    public String toString() { return "style " + color; }
                    public static class class_2584 {
                        public static final com.mojang.serialization.MapCodec<class_2583> field_46612 = null;
                        public static final com.mojang.serialization.Codec<class_2583> field_46613 = null;
                    }
                }""");
        host.put("net/minecraft/class_8824.java", """
                package net.minecraft;
                import com.google.gson.*;
                import com.mojang.serialization.*;
                public final class class_8824 {
                    public static String opsSeen;
                    public static final Codec<class_2561> field_46597 = new Codec<>() {
                        public <T> DataResult<class_2561> parse(DynamicOps<T> ops, T input) {
                            opsSeen = ops.getClass().getName();
                            try {
                                return DataResult.success(read((JsonElement) input));
                            } catch (RuntimeException failure) {
                                return DataResult.error(failure.getMessage());
                            }
                        }
                        @SuppressWarnings("unchecked")
                        public <T> DataResult<T> encodeStart(DynamicOps<T> ops, class_2561 input) {
                            class_5250 text = (class_5250) input;
                            if (text.style.method_10967()) return DataResult.success((T) new JsonPrimitive(text.text));
                            JsonObject json = new JsonObject();
                            json.addProperty("text", text.text);
                            json.addProperty("color", text.style.color);
                            return DataResult.success((T) json);
                        }
                    };
                    static class_5250 read(JsonElement json) {
                        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) return new class_5250(json.getAsString(), new class_2583(null));
                        JsonObject object = json.getAsJsonObject();
                        for (String key : object.keySet()) {
                            if (!java.util.Set.of("text", "translate", "color", "extra", "bold", "italic").contains(key)) throw new IllegalStateException("No matching codec found: " + key);
                        }
                        for (String flag : java.util.List.of("bold", "italic")) {
                            if (object.has(flag) && !object.get(flag).getAsJsonPrimitive().isBoolean()) throw new IllegalStateException("Not a boolean: " + object.get(flag));
                        }
                        if (object.has("extra")) for (JsonElement sibling : object.getAsJsonArray("extra")) read(sibling);
                        JsonElement text = object.has("text") ? object.get("text") : object.get("translate");
                        String bold = object.has("bold") && object.get("bold").getAsBoolean() ? " bold" : "";
                        return new class_5250(text.getAsString() + bold, new class_2583(object.has("color") ? object.get("color").getAsString() : null));
                    }
                }""");
        host.put("net/minecraft/class_3551.java", """
                package net.minecraft;
                public class class_3551 {
                    public static int calls;
                    public static com.mojang.datafixers.DataFixer method_15450() {
                        return new com.mojang.datafixers.DataFixer() {
                            public <T> com.mojang.serialization.Dynamic<T> update(com.mojang.datafixers.DSL.TypeReference type,
                                    com.mojang.serialization.Dynamic<T> input, int version, int newVersion) {
                                calls++;
                                return input;
                            }
                        };
                    }
                }""");
        // References: 1.21.5 added the text component type its text fixer runs on.
        host.put("net/minecraft/class_1208.java", "package net.minecraft; public class class_1208 {" + (fixer
                ? " public static final com.mojang.datafixers.DSL.TypeReference field_55639 = new com.mojang.datafixers.DSL.TypeReference() {};"
                : "") + " }");
        host.put("net/minecraft/class_155.java", "package net.minecraft; public class class_155 { public static final int field_29732 = 4440; }");
        host.put("net/minecraft/server/MinecraftServer.java", """
                package net.minecraft.server;
                public class MinecraftServer {
                    public net.minecraft.class_5455 method_30611() { return new net.minecraft.class_5455() {}; }
                }""");
        host.put("net/fabricmc/loader/api/FabricLoader.java", """
                package net.fabricmc.loader.api;
                public final class FabricLoader {
                    public static Object game;
                    private static final FabricLoader INSTANCE = new FabricLoader();
                    public static FabricLoader getInstance() { return INSTANCE; }
                    public Object getGameInstance() { return game; }
                    public Object getEnvironmentType() { return "SERVER"; }
                }""");
        host.put("test/World.java", """
                package test;
                public final class World {
                    public static void start() { net.fabricmc.loader.api.FabricLoader.game = new net.minecraft.server.MinecraftServer(); }
                }""");
        return host;
    }
}
