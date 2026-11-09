/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.google.gson.JsonParser;
import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Forge 26.2 failed to load a world with Sophisticated Backpacks 1.20.1: its loot function type, Retromod's stand-in
 * for the record 26.1 deleted, went into a registry that holds map codecs, and the loot table's dispatch cast it to one.
 */
class LootTypeStandInTest {

    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String FUNCTION = WorldgenTypeBridgeSynthetic.LOOT_FUNCTION_WRAPPER;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a function type registered as it is reads the loot table entry through the registry's dispatch")
    void standInReadsThroughTheDispatch() throws Exception {
        Dfu dfu = new Dfu(Map.of(FUNCTION, WorldgenTypeBridgeSynthetic.generateWrapper(FUNCTION)));
        Object codec = dfu.fieldCodec("name");
        Object type = dfu.type(FUNCTION).getConstructor(dfu.type(MAP_CODEC)).newInstance(codec);

        assertTrue(dfu.type(MAP_CODEC).isInstance(type), "the registry's dispatch casts its value to MapCodec");
        assertSame(codec, type.getClass().getDeclaredMethod("codec").invoke(type), "the mod's codec() still answers");
        String json = "{\"type\":\"sophisticatedbackpacks:copy_backpack_data\",\"name\":\"backpack\"}";
        assertEquals(Optional.of("backpack"), dfu.parse(dfu.dispatch(type), json));
        assertEquals(dfu.encode(dfu.dispatch(codec), "backpack"), dfu.encode(dfu.dispatch(type), "backpack"),
                "writing the entry is unchanged");
    }

    @Test
    @DisplayName("a value that is not a map codec fails the same dispatch, as the stand-in did in the game")
    void plainHolderFailsTheDispatch() throws Exception {
        Dfu dfu = new Dfu(Map.of());
        Throwable failure = assertThrows(Throwable.class, () -> dfu.parse(dfu.dispatch(new Object()), "{\"type\":\"x\"}"));
        assertInstanceOf(ClassCastException.class,
                failure instanceof InvocationTargetException wrapped ? wrapped.getCause() : failure);
    }

    @Test
    @DisplayName("each loot type record 26.1 deleted gets a stand-in that extends MapCodec, and the mod's references move to it")
    void everyDeletedRecordHasAStandIn() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        WorldgenTypeBridgeSynthetic.registerPre26_2(transformer);

        assertEquals(6, WorldgenTypeBridgeSynthetic.LOOT_TYPE_STAND_INS.length);
        for (String[] standIn : WorldgenTypeBridgeSynthetic.LOOT_TYPE_STAND_INS) {
            byte[] bytes = transformer.getSyntheticClasses().get(standIn[1]);
            assertNotNull(bytes, standIn[1] + " must be registered");
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            assertEquals(MAP_CODEC, node.superName, standIn[1]);
            List<String> methods = new ArrayList<>();
            node.methods.forEach(method -> methods.add(method.name + method.desc));
            for (String expected : List.of("<init>(L" + MAP_CODEC + ";)V", "codec()L" + MAP_CODEC + ";",
                    "decode(Lcom/mojang/serialization/DynamicOps;Lcom/mojang/serialization/MapLike;)"
                            + "Lcom/mojang/serialization/DataResult;")) {
                assertTrue(methods.contains(expected), standIn[1] + " lacks " + expected);
            }
            assertEquals(standIn[1], transformer.getClassRedirects().get(standIn[0]), standIn[0]);
        }
    }

    /** DataFixerUpper 10 and fastutil from the local Prism Launcher libraries, with {@code classes} defined first. */
    static final class Dfu {
        private final ClassLoader loader;

        Dfu(Map<String, byte[]> classes) throws Exception {
            Path libraries = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
            Path dfu = libraries.resolve("com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar");
            Path fastutil = libraries.resolve("it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar");
            assumeTrue(Files.isRegularFile(dfu) && Files.isRegularFile(fastutil),
                    "no local DataFixerUpper 10 or fastutil jar; they are local test material");
            loader = new LegacyKeyDispatchCodecBridgeTest.ChildFirstLoader(
                    new URL[] {dfu.toUri().toURL(), fastutil.toUri().toURL()}, classes);
        }

        Class<?> type(String internalName) throws ClassNotFoundException {
            return loader.loadClass(internalName.replace('/', '.'));
        }

        /** {@code Codec.STRING.fieldOf(name)}, a map codec as a loot function's codec is. */
        Object fieldCodec(String name) throws Exception {
            return type(CODEC).getMethod("fieldOf", String.class).invoke(type(CODEC).getField("STRING").get(null), name);
        }

        /** {@code Codec.STRING.dispatch(...)} whose every type reads {@code registered}, as a registry's dispatch does. */
        Object dispatch(Object registered) throws Exception {
            Function<Object, Object> typeOf = value -> "any";
            Function<Object, Object> codecOf = type -> registered;
            return type(CODEC).getMethod("dispatch", Function.class, Function.class)
                    .invoke(type(CODEC).getField("STRING").get(null), typeOf, codecOf);
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
}
