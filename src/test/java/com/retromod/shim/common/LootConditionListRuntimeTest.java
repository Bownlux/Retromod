/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.loaderapi.CliHarness;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.*;

/**
 * NeoForge 26.3 stopped at its loot tables with a Farmer's Delight style function: 26.3's condition field gives one
 * optional holder, and the 1.21.1 constructor casts it to a list. Farmer's Delight 1.21.1, translated for NeoForge 26.3,
 * reads and writes its function here against real DataFixerUpper and the real 26.3 base class.
 */
class LootConditionListRuntimeTest {

    private static final Path LIBRARIES = Path.of(System.getProperty("user.home"),
            "Library/Application Support/PrismLauncher/libraries");
    private static final Path FARMERS_DELIGHT = Path.of(System.getProperty("user.home"),
            "Development/Minecraft/RetroMod/.retromod-work/survey/dl-neoforge1211/FarmersDelight-1.21.1-1.3.4.jar");
    private static final String CONDITION = "net/minecraft/world/level/storage/loot/predicates/LootItemCondition";
    private static final String FUNCTION = "vectorwing.farmersdelight.common.loot.function.CopySkilletFunction";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";

    /** The loader the stub condition codec is built in; set before the stub loads. */
    private static volatile ClassLoader host;

    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Farmer's Delight's copy skillet function reads its one condition, writes it back, and builds")
    void translatedFunctionReadsTheCondition() throws Throwable {
        ClassLoader loader = hostWithTranslatedMod();
        Object codec = loader.loadClass("com.mojang.serialization.MapCodec").getMethod("codec")
                .invoke(loader.loadClass(FUNCTION).getField("CODEC").get(null));

        Object function = parse(loader, codec, "{\"condition\":{\"type\":\"test:always\"}}");
        Optional<?> condition = condition(loader, function);
        assertTrue(condition.isPresent(), "the 1.21.1 constructor passed the condition on to 26.3's field");
        Object held = loader.loadClass("net.minecraft.core.Holder").getMethod("value").invoke(condition.get());
        assertEquals("test:always", held.toString(), "the condition the data named");
        assertEquals(JsonParser.parseString("{\"condition\":{\"type\":\"test:always\"}}"), encode(loader, codec, function),
                "writing goes through 26.3's own condition field");

        assertTrue(condition(loader, parse(loader, codec, "{}")).isEmpty(), "no condition reads as none");
        // The builder a mod's loot table code uses, which hands the old constructor a list too.
        Object builder = loader.loadClass(FUNCTION).getMethod("builder").invoke(null);
        Object built = loader.loadClass("net.minecraft.world.level.storage.loot.functions.LootItemFunction$Builder")
                .getMethod("build").invoke(builder);
        assertEquals(FUNCTION, built.getClass().getName());
        assertTrue(condition(loader, built).isEmpty());
    }

    /** Public, so the stub condition type in the test loader can call it. */
    public static final class Stubs {
        private Stubs() {}

        /** {@code LootItemCondition.CODEC} as the stub declares it: {@code {"type": name}} read as a direct holder. */
        public static Object conditionCodec() throws ReflectiveOperationException {
            Class<?> codec = host.loadClass("com.mojang.serialization.Codec");
            Class<?> mapCodec = host.loadClass("com.mojang.serialization.MapCodec");
            Class<?> holder = host.loadClass("net.minecraft.core.Holder");
            Class<?> condition = host.loadClass(CONDITION.replace('/', '.'));
            Object typeField = codec.getMethod("fieldOf", String.class).invoke(codec.getField("STRING").get(null), "type");
            Object typeCodec = mapCodec.getMethod("codec").invoke(typeField);
            Function<Object, Object> read = type -> {
                Object named = Proxy.newProxyInstance(host, new Class<?>[] {condition},
                        (proxy, method, args) -> method.getName().equals("toString") ? type : null);
                try {
                    return holder.getMethod("direct", Object.class).invoke(null, named);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            };
            Function<Object, Object> write = held -> {
                try {
                    return holder.getMethod("value").invoke(held).toString();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            };
            return codec.getMethod("xmap", Function.class, Function.class).invoke(typeCodec, read, write);
        }
    }

    /** Farmer's Delight translated for NeoForge 26.3 on the 26.3 client classes, with a stub condition type. */
    private ClassLoader hostWithTranslatedMod() throws Exception {
        Path client = LIBRARIES.resolve("com/mojang/minecraft/26.3/minecraft-26.3-client.jar");
        Path dfu = LIBRARIES.resolve("com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar");
        Path fastutil = LIBRARIES.resolve("it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar");
        Path guava = LIBRARIES.resolve("com/google/guava/guava/33.5.0-jre/guava-33.5.0-jre.jar");
        for (Path material : new Path[] {FARMERS_DELIGHT, client, dfu, fastutil, guava}) {
            assumeTrue(Files.isRegularFile(material), "local test material is missing: " + material);
        }
        Path translated = dir.resolve("farmersdelight-26.3.jar");
        McReflect.setForceNeoForge(true);
        ModVersionDetector.setDefaultPreferredLoader("neoforge");
        CliHarness.transform(FARMERS_DELIGHT, translated, "26.3");
        assertTrue(Files.isRegularFile(translated), "the CLI wrote the translated jar");

        URL[] urls = {translated.toUri().toURL(), dfu.toUri().toURL(), fastutil.toUri().toURL(), guava.toUri().toURL()};
        // The client jar is signed, so its classes are defined from their bytes, beside the unsigned stubs.
        ClassLoader loader = new LegacyKeyDispatchCodecBridgeTest.ChildFirstLoader(urls,
                new ClientClasses(client, Map.of(CONDITION, stubCondition(), IDENTIFIER, stubIdentifier())));
        host = loader;
        return loader;
    }

    /** {@code interface LootItemCondition { Codec CODEC = Stubs.conditionCodec(); }}: the real one needs the registries. */
    private static byte[] stubCondition() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, CONDITION, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "CODEC", "Lcom/mojang/serialization/Codec;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitMethodInsn(INVOKESTATIC, "com/retromod/shim/common/LootConditionListRuntimeTest$Stubs", "conditionCodec",
                "()Ljava/lang/Object;", false);
        init.visitTypeInsn(CHECKCAST, "com/mojang/serialization/Codec");
        init.visitFieldInsn(PUTSTATIC, CONDITION, "CODEC", "Lcom/mojang/serialization/Codec;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code Identifier} with only {@code fromNamespaceAndPath}, which the function's id needs: the real one needs Netty. */
    private static byte[] stubIdentifier() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, IDENTIFIER, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor of = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", null, null);
        of.visitCode();
        of.visitTypeInsn(NEW, IDENTIFIER);
        of.visitInsn(DUP);
        of.visitMethodInsn(INVOKESPECIAL, IDENTIFIER, "<init>", "()V", false);
        of.visitInsn(ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The client jar's classes by internal name, read when asked for, with stubs in place of some. */
    private static final class ClientClasses extends java.util.AbstractMap<String, byte[]> {
        private final java.util.zip.ZipFile jar;
        private final Map<String, byte[]> stubs;

        ClientClasses(Path client, Map<String, byte[]> stubs) throws java.io.IOException {
            this.jar = new java.util.zip.ZipFile(client.toFile());
            this.stubs = stubs;
        }

        @Override
        public boolean containsKey(Object name) {
            return stubs.containsKey(name) || jar.getEntry(name + ".class") != null;
        }

        @Override
        public byte[] get(Object name) {
            if (stubs.containsKey(name)) return stubs.get(name);
            java.util.zip.ZipEntry entry = jar.getEntry(name + ".class");
            if (entry == null) return null;
            try (java.io.InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public java.util.Set<Entry<String, byte[]>> entrySet() {
            throw new UnsupportedOperationException();
        }
    }

    private static Object parse(ClassLoader loader, Object codec, String json) throws Throwable {
        Object ops = loader.loadClass("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null);
        Object result = invoke(loader.loadClass("com.mojang.serialization.Decoder").getMethod("parse",
                loader.loadClass("com.mojang.serialization.DynamicOps"), Object.class), codec, ops, JsonParser.parseString(json));
        return invoke(loader.loadClass("com.mojang.serialization.DataResult").getMethod("getOrThrow"), result);
    }

    private static JsonElement encode(ClassLoader loader, Object codec, Object value) throws Throwable {
        Object ops = loader.loadClass("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null);
        Object result = invoke(loader.loadClass("com.mojang.serialization.Encoder").getMethod("encodeStart",
                loader.loadClass("com.mojang.serialization.DynamicOps"), Object.class), codec, ops, value);
        return (JsonElement) invoke(loader.loadClass("com.mojang.serialization.DataResult").getMethod("getOrThrow"), result);
    }

    /** The real 26.3 base class's {@code condition} field. */
    private static Optional<?> condition(ClassLoader loader, Object function) throws ReflectiveOperationException {
        Field field = loader.loadClass("net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction")
                .getDeclaredField("condition");
        field.setAccessible(true);
        return (Optional<?>) field.get(function);
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object... args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
