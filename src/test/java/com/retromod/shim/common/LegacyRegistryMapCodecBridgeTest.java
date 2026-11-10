/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.*;

/**
 * Forge 26.2 failed to load a world with Balm and Farmer's Delight 1.20.1: their biome modifier serializers are
 * codecs, and the registry's dispatch casts each to a map codec. The generated value is both, against the real
 * DataFixerUpper 10 (Minecraft 26.x) and compared with DataFixerUpper 6 (1.20.1).
 */
class LegacyRegistryMapCodecBridgeTest {

    private static final Path LIBRARIES = Path.of(System.getProperty("user.home"),
            "Library/Application Support/PrismLauncher/libraries");
    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String BIOME_MODIFIERS = "ResourceKey[minecraft:root / forge:biome_modifier_serializers]";
    private static final String PROCESSOR_TYPE = LegacyRegistryMapCodecBridge.PROCESSOR_TYPE;

    @Test
    @DisplayName("Balm's unit codec: the raw codec fails the dispatch as in the game, the converted one loads")
    void unitCodecLoadsThroughTheDispatch() throws Exception {
        Dfu dfu = new Dfu("10.0.21", true);
        // DataFixerUpper 10 has no Codec.unit; the translated mod calls MapCodec.unitCodec, a plain codec.
        Object unit = dfu.unitCodec("balm modifier");

        Throwable raw = assertThrows(Throwable.class, () -> dfu.parse(dfu.dispatch(unit), "{\"type\":\"balm:balm\"}"));
        assertInstanceOf(ClassCastException.class, raw instanceof InvocationTargetException wrapped ? wrapped.getCause() : raw,
                "DataFixerUpper 10 casts the registered value to MapCodec");

        Object registered = dfu.of(unit);
        assertTrue(dfu.type(MAP_CODEC).isInstance(registered), "the dispatch reads a map codec");
        assertTrue(dfu.type(CODEC).isInstance(registered), "the mod still reads its entry as a Codec");
        assertEquals(Optional.of("balm modifier"), dfu.parse(dfu.dispatch(registered), "{\"type\":\"balm:balm\"}"));
    }

    @Test
    @DisplayName("Farmer's Delight's record codec reads its fields inline, and as a Codec it reads as it always did")
    void recordCodecReadsInline() throws Exception {
        Dfu dfu = new Dfu("10.0.21", true);
        Object record = dfu.recordCodec("feature");
        Object registered = dfu.of(record);

        assertEquals(Optional.of("minecraft:brown_mushroom"),
                dfu.parse(dfu.dispatch(registered), "{\"type\":\"farmersdelight:add_features_by_filter\","
                        + "\"feature\":\"minecraft:brown_mushroom\"}"));
        assertEquals(dfu.parse(record, "{\"feature\":\"x\"}"), dfu.parse(registered, "{\"feature\":\"x\"}"));
        assertEquals(dfu.encode(record, "x"), dfu.encode(registered, "x"), "encoding through the Codec view is unchanged");
    }

    @Test
    @DisplayName("any other codec reads the data Forge 1.20.1's dispatch read, under the value key")
    void otherCodecsReadLikeForge1201() throws Exception {
        String data = "{\"type\":\"mod:plain\",\"value\":\"abc\"}";
        Dfu old = new Dfu("6.0.8", false);
        Optional<?> expected = old.parse(old.dispatch(old.codecField("STRING")), data);
        assertEquals(Optional.of("abc"), expected, "DataFixerUpper 6 reads a plain codec under \"value\"");

        Dfu dfu = new Dfu("10.0.21", true);
        assertEquals(expected, dfu.parse(dfu.dispatch(dfu.of(dfu.codecField("STRING"))), data));
    }

    @Test
    @DisplayName("map codecs, converted values, other objects and null pass through unchanged")
    void unchangedValues() throws Exception {
        Dfu dfu = new Dfu("10.0.21", true);
        Object mapCodec = dfu.type(MAP_CODEC).getMethod("unit", Object.class).invoke(null, "x");
        Object converted = dfu.of(dfu.codecField("STRING"));
        assertSame(mapCodec, dfu.of(mapCodec), "a 1.20.5 mod's map codec");
        assertSame(converted, dfu.of(converted));
        Object block = new Object();
        assertSame(block, dfu.of(block));
        assertNull(dfu.of(null));
    }

    @Test
    @DisplayName("the register wrappers' entry point converts only for a map codec registry")
    void serializerEntryPoint() throws Exception {
        Dfu dfu = new Dfu("10.0.21", true);
        Object unit = dfu.unitCodec("balm modifier");
        Supplier<Object> supplier = () -> unit;

        Supplier<?> biome = dfu.serializer(BIOME_MODIFIERS, supplier);
        assertTrue(dfu.type(MAP_CODEC).isInstance(biome.get()));
        assertSame(supplier, dfu.serializer("ResourceKey[minecraft:root / minecraft:block]", supplier));
    }

    @Test
    @DisplayName("on 26.2 an old structure processor type becomes one value: the registry's map codec and the mod's type")
    void processorTypeBecomesBothViews() throws Exception {
        Dfu dfu = new Dfu(Map.of(LegacyRegistryMapCodecBridge.CLASS, LegacyRegistryMapCodecBridge.generate(true, true),
                LegacyRegistryMapCodecBridge.PROCESSOR_VIEW, WorldgenTypeBridgeSynthetic.generateWrapper(
                        LegacyRegistryMapCodecBridge.PROCESSOR_VIEW, PROCESSOR_TYPE),
                PROCESSOR_TYPE, processorTypeInterface(),
                "mod/MapProcessorType", processorType("mod/MapProcessorType", true),
                "mod/CodecProcessorType", processorType("mod/CodecProcessorType", false)));
        String data = "{\"type\":\"cataclysm:processor\",\"block\":\"stone\"}";
        // A 1.21 lambda returns a map codec; a 1.20.1 one returns its record codec.
        for (String name : List.of("mod/MapProcessorType", "mod/CodecProcessorType")) {
            Object type = dfu.type(name).getConstructor().newInstance();
            Object registered = dfu.of(type);
            assertTrue(dfu.type(MAP_CODEC).isInstance(registered), name + ": the dispatch reads a map codec");
            assertTrue(dfu.type(PROCESSOR_TYPE).isInstance(registered), name + ": the mod's holder still casts to its type");
            assertEquals(Optional.of("stone"), dfu.parse(dfu.dispatch(registered), data), name);
            assertSame(registered, dfu.of(registered), name + ": converting again changes nothing");
        }
        Supplier<Object> supplier = () -> new Object();
        assertNotSame(supplier, dfu.serializer("ResourceKey[minecraft:root / minecraft:worldgen/structure_processor]", supplier),
                "the register wrappers convert what goes into the structure processor registry");
    }

    @Test
    @DisplayName("before 26.2 a structure processor type is the registry's own value and stays as the mod built it")
    void processorTypeStaysBefore262() throws Exception {
        Dfu dfu = new Dfu(Map.of(LegacyRegistryMapCodecBridge.CLASS, LegacyRegistryMapCodecBridge.generate(true, false),
                PROCESSOR_TYPE, processorTypeInterface(),
                "mod/MapProcessorType", processorType("mod/MapProcessorType", true)));
        Object type = dfu.type("mod/MapProcessorType").getConstructor().newInstance();
        assertSame(type, dfu.of(type));
    }

    @Test
    @DisplayName("with processor views the value class still loads and converts codecs where no processor type exists")
    void valueClassLoadsWithoutTheProcessorType() throws Exception {
        // Every register wrapper calls this class, so naming the processor type in its declaration broke all of them.
        Dfu dfu = new Dfu(Map.of(LegacyRegistryMapCodecBridge.CLASS, LegacyRegistryMapCodecBridge.generate(true, true)));
        Object registered = dfu.of(dfu.unitCodec("balm modifier"));
        assertTrue(dfu.type(MAP_CODEC).isInstance(registered));
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(LegacyRegistryMapCodecBridge.generate(true, true)).accept(node, 0);
        assertEquals(List.of(CODEC), node.interfaces);
    }

    /** The 26.2 {@code StructureProcessorType}: an interface that only holds codecs, with no {@code codec()} to implement. */
    private static byte[] processorTypeInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT
                | ACC_INTERFACE, PROCESSOR_TYPE, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** What a mod's {@code () -> CODEC} lambda is: a type whose {@code codec()} returns a map codec or, from 1.20.1, a codec. */
    private static byte[] processorType(String name, boolean mapCodec) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object",
                new String[] {PROCESSOR_TYPE});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        // codec(): Codec.STRING.fieldOf("block"), and .codec() of it for the 1.20.1 record codec.
        String returned = mapCodec ? MAP_CODEC : CODEC;
        MethodVisitor codec = cw.visitMethod(ACC_PUBLIC, "codec",
                "()L" + returned + ";", null, null);
        codec.visitCode();
        codec.visitFieldInsn(GETSTATIC, CODEC, "STRING", "Lcom/mojang/serialization/codecs/PrimitiveCodec;");
        codec.visitLdcInsn("block");
        codec.visitMethodInsn(INVOKEINTERFACE, CODEC, "fieldOf", "(Ljava/lang/String;)L" + MAP_CODEC + ";", true);
        if (!mapCodec) {
            codec.visitMethodInsn(INVOKEVIRTUAL, MAP_CODEC, "codec", "()L" + CODEC + ";", false);
        }
        codec.visitInsn(ARETURN);
        codec.visitMaxs(0, 0);
        codec.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** DataFixerUpper and fastutil from the local Prism Launcher libraries, with the generated class when asked. */
    private static final class Dfu {
        private final ClassLoader loader;

        Dfu(String version, boolean generated) throws Exception {
            this(version, generated
                    ? Map.of(LegacyRegistryMapCodecBridge.CLASS, LegacyRegistryMapCodecBridge.generate(true)) : Map.of());
        }

        /** DataFixerUpper 10 with {@code classes}, such as a host type the generated class implements. */
        Dfu(Map<String, byte[]> classes) throws Exception {
            this("10.0.21", classes);
        }

        private Dfu(String version, Map<String, byte[]> classes) throws Exception {
            Path dfu = LIBRARIES.resolve("com/mojang/datafixerupper/" + version + "/datafixerupper-" + version + ".jar");
            Path fastutil = LIBRARIES.resolve("it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar");
            assumeTrue(Files.isRegularFile(dfu) && Files.isRegularFile(fastutil),
                    "no local DataFixerUpper " + version + " or fastutil jar; they are local test material");
            loader = new LegacyKeyDispatchCodecBridgeTest.ChildFirstLoader(
                    new URL[] {dfu.toUri().toURL(), fastutil.toUri().toURL()}, classes);
        }

        Class<?> type(String internalName) throws ClassNotFoundException {
            return loader.loadClass(internalName.replace('/', '.'));
        }

        Object codecField(String name) throws Exception {
            return type(CODEC).getField(name).get(null);
        }

        Object unitCodec(Object value) throws Exception {
            return type(MAP_CODEC).getMethod("unitCodec", Object.class).invoke(null, value);
        }

        /** {@code Codec.STRING.fieldOf(field).codec()}, which RecordCodecBuilder.create also returns. */
        Object recordCodec(String field) throws Exception {
            Object fieldCodec = type(CODEC).getMethod("fieldOf", String.class).invoke(codecField("STRING"), field);
            return type(MAP_CODEC).getMethod("codec").invoke(fieldCodec);
        }

        Object of(Object value) throws Exception {
            return type(LegacyRegistryMapCodecBridge.CLASS).getMethod("of", Object.class).invoke(null, value);
        }

        Supplier<?> serializer(String key, Supplier<?> supplier) throws Exception {
            return (Supplier<?>) type(LegacyRegistryMapCodecBridge.CLASS)
                    .getMethod("serializer", Object.class, Supplier.class).invoke(null, key, supplier);
        }

        /** {@code Codec.STRING.dispatch(...)} whose every type reads {@code registered}, as a registry's dispatch does. */
        Object dispatch(Object registered) throws Exception {
            Function<Object, Object> typeOf = value -> "any";
            Function<Object, Object> codecOf = type -> registered;
            return type(CODEC).getMethod("dispatch", Function.class, Function.class)
                    .invoke(codecField("STRING"), typeOf, codecOf);
        }

        Optional<?> parse(Object codec, String json) throws Exception {
            Object ops = type("com/mojang/serialization/JsonOps").getField("INSTANCE").get(null);
            Object result = type(CODEC).getMethod("parse", type("com/mojang/serialization/DynamicOps"), Object.class)
                    .invoke(codec, ops, JsonParser.parseString(json));
            return (Optional<?>) type("com/mojang/serialization/DataResult").getMethod("result").invoke(result);
        }

        Optional<?> encode(Object codec, Object value) throws Exception {
            Object ops = type("com/mojang/serialization/JsonOps").getField("INSTANCE").get(null);
            Object result = type(CODEC).getMethod("encodeStart", type("com/mojang/serialization/DynamicOps"), Object.class)
                    .invoke(codec, ops, value);
            return (Optional<?>) type("com/mojang/serialization/DataResult").getMethod("result").invoke(result);
        }
    }

    @Test
    @DisplayName("the generated class declares both views and the entry points the wrappers and redirects call")
    void generatedShape() {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(LegacyRegistryMapCodecBridge.generate(true)).accept(node, 0);
        assertEquals(MAP_CODEC, node.superName);
        assertEquals(List.of(CODEC), node.interfaces);
        List<String> methods = new ArrayList<>();
        node.methods.forEach(method -> methods.add(method.name + method.desc));
        for (String expected : List.of("of(Ljava/lang/Object;)Ljava/lang/Object;",
                "serializer" + LegacyRegistryMapCodecBridge.SERIALIZER_DESC,
                "registerEach" + LegacyRegistryMapCodecBridge.REGISTER_EACH_DESC,
                "registerDeferred" + LegacyRegistryMapCodecBridge.REGISTER_DEFERRED_DESC)) {
            assertTrue(methods.contains(expected), expected + " in " + methods);
        }
    }
}
