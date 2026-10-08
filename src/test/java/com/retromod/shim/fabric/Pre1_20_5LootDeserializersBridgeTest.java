/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.embedded.LegacyLootGson;
import com.retromod.util.HostLibraryNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A 1.20.1 mod that reads its own loot pools with {@code Deserializers.createLootTableSerializer()}
 * (Palladium's loot table additions do) must keep reading them on a 1.20.5+ Fabric host, where
 * the Gson serializers were replaced by codecs.
 */
class Pre1_20_5LootDeserializersBridgeTest {

    private static final String STUBS = "com/retromod/shim/fabric/lootstub/";
    private static final Map<String, String> HOST_NAMES = Map.of(
            STUBS + "DynamicOps", "com/mojang/serialization/DynamicOps",
            STUBS + "JsonOps", "com/mojang/serialization/JsonOps",
            STUBS + "LootTable", "net/minecraft/class_52",
            STUBS + "LootPool", "net/minecraft/class_55",
            STUBS + "LootCondition", "net/minecraft/class_5341",
            STUBS + "LootFunction", "net/minecraft/class_117",
            STUBS + "LootFunctions", "net/minecraft/class_131");

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Deserializers.createLootTableSerializer() goes through the generated factory")
    void oldSerializerCallIsRedirected() {
        Pre1_20_5LootDeserializersBridge.registerRedirects(transformer);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/LootModifications", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, Pre1_20_5LootDeserializersBridge.DESERIALIZERS, "method_27862",
                Pre1_20_5LootDeserializersBridge.builderDesc(), false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = transformer.transformClass(cw.toByteArray(), "test/LootModifications");

        MethodInsnNode call = calls(read(out)).get(0);
        assertEquals(Pre1_20_5LootDeserializersBridge.FACTORY, call.owner,
                "no call to the removed Deserializers class may remain");
        assertEquals(Pre1_20_5LootDeserializersBridge.builderDesc(), call.desc,
                "the caller still receives a GsonBuilder");
        assertTrue(transformer.getSyntheticClasses().containsKey(Pre1_20_5LootDeserializersBridge.SUPPORT),
                "the runtime support class must be embedded with the factory");
    }

    @Test
    @DisplayName("The generated factory returns the game's GsonBuilder, not a relocated copy")
    void factoryNamesTheHostGson() {
        ClassNode factory = read(Pre1_20_5LootDeserializersBridge.generateFactory());

        for (Pre1_20_5LootDeserializersBridge.Factory expected : Pre1_20_5LootDeserializersBridge.FACTORIES) {
            MethodNode method = factory.methods.stream().filter(m -> m.name.equals(expected.name()))
                    .findFirst().orElseThrow(() -> new AssertionError("missing " + expected.name()));
            assertEquals("()L" + HostLibraryNames.GSON_BUILDER + ";", method.desc);
        }
    }

    @Test
    @DisplayName("The loot table Gson reads pools, tables, conditions and functions through their codecs")
    void lootTableGsonDecodesThroughCodecs() throws Exception {
        Gson gson = lootTableBuilder().create();

        Object pool = gson.fromJson(JsonParser.parseString("{\"name\":\"diamonds\"}"),
                stubLoader.loadClass("net.minecraft.class_55"));
        Object table = gson.fromJson(JsonParser.parseString("{\"name\":\"chest\"}"),
                stubLoader.loadClass("net.minecraft.class_52"));
        Object condition = gson.fromJson(JsonParser.parseString("{\"name\":\"chance\"}"),
                stubLoader.loadClass("net.minecraft.class_5341"));
        Object function = gson.fromJson(JsonParser.parseString("{\"name\":\"set_count\"}"),
                stubLoader.loadClass("net.minecraft.class_117"));

        assertEquals("diamonds", nameOf(pool));
        assertEquals("chest", nameOf(table));
        assertEquals("chance", nameOf(condition));
        assertEquals("set_count", nameOf(function));
        assertEquals("\"diamonds\"", gson.toJson(pool), "writing a pool goes through its codec as well");
    }

    @Test
    @DisplayName("A pool the codec rejects fails as a Gson parse error carrying the codec's message")
    void rejectedPoolIsAParseError() throws Exception {
        Gson gson = lootTableBuilder().create();
        Class<?> poolType = stubLoader.loadClass("net.minecraft.class_55");

        JsonParseException error = assertThrows(JsonParseException.class,
                () -> gson.fromJson(JsonParser.parseString("{\"bad\":true}"), poolType));

        assertTrue(error.getMessage().contains("Unknown registry key: bad"), error.getMessage());
    }

    // ---- fixtures ------------------------------------------------------------------------

    private HostRenamingLoader stubLoader;

    /** Runs the embedded support class against stand-ins renamed to the host's class names. */
    private GsonBuilder lootTableBuilder() throws Exception {
        stubLoader = new HostRenamingLoader(STUBS, HOST_NAMES, LegacyLootGson.class);
        Class<?> support = stubLoader.loadClass(LegacyLootGson.class.getName());
        return (GsonBuilder) support.getMethod("lootTableBuilder").invoke(null);
    }

    private static String nameOf(Object lootValue) throws Exception {
        return (String) lootValue.getClass().getMethod("name").invoke(lootValue);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<MethodInsnNode> calls(ClassNode node) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) calls.add(call);
            }
        }
        return calls;
    }
}
