/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.util.RetromodLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A serializer with no codec() is the normal case for a 1.20.1 mod and logs one detail line; a codec() that throws still warns. */
class LegacyRecipeSerializerLogTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @TempDir
    Path dir;

    @AfterEach
    void clear() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Adapting a 1.20.1 serializer, which has no codec(), logs one detail line and no warning")
    void missingCodecIsADetailLine() throws Throwable {
        BridgeHarness.Run run = game();

        Object adapted = run.call(LegacyRecipeSerializerShim.BRIDGE, "adapt", newObject(run, "mod.JsonSerializer"));

        assertEquals("net.minecraft.world.item.crafting.RecipeSerializer", adapted.getClass().getName(),
                "the serializer is still registered, as a host serializer");
        assertEquals(List.of("debug " + RetromodLog.DETAIL_NAME + " [Retromod] Registered the legacy recipe serializer"
                + " mod.JsonSerializer without codecs, since it has no codec() method"), lines(run));
    }

    @Test
    @DisplayName("A codec() that throws still warns with its cause")
    void throwingCodecWarns() throws Throwable {
        BridgeHarness.Run run = game();

        run.call(LegacyRecipeSerializerShim.BRIDGE, "adapt", newObject(run, "mod.BrokenSerializer"));

        assertEquals(List.of("warn [Retromod] Could not read the codecs of the legacy recipe serializer mod.BrokenSerializer,"
                + " so Retromod registered it without them (java.lang.reflect.InvocationTargetException)"), lines(run));
    }

    // ---- the game ----

    private BridgeHarness.Run game() throws Exception {
        transformer.registerSyntheticClass(LegacyRecipeSerializerShim.BRIDGE, LegacyRecipeSerializerShim.generateBridge());
        transformer.registerSyntheticClass(LegacyRecipeSerializerShim.SAFE_CODEC, LegacyRecipeSerializerShim.generateSafeCodec());
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("host"), hostSources());
        Map<String, byte[]> mod = BridgeHarness.compile(dir.resolve("mod"), modSources());
        return new BridgeHarness.Run(host, BridgeHarness.only(mod, "mod/"));
    }

    private static Object newObject(BridgeHarness.Run run, String name) throws Exception {
        return run.loadClass(name).getConstructor().newInstance();
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(BridgeHarness.Run run) throws Exception {
        return (List<String>) run.loadClass("org.slf4j.LoggerFactory").getField("LINES").get(null);
    }

    // The 26.x serializer record, its codecs as far as the bridge uses them, and a game logger that keeps every line it gets.
    private static Map<String, String> hostSources() {
        Map<String, String> sources = new HashMap<>(Map.of(
                "com/mojang/serialization/DynamicOps.java", "package com.mojang.serialization; public interface DynamicOps<T> {}",
                "com/mojang/serialization/MapLike.java", "package com.mojang.serialization; public interface MapLike<T> {}",
                "com/mojang/serialization/RecordBuilder.java", "package com.mojang.serialization; public interface RecordBuilder<T> {}",
                "com/mojang/serialization/DataResult.java", """
                        package com.mojang.serialization;
                        public interface DataResult<R> {
                            static <R> DataResult<R> error(java.util.function.Supplier<String> message) { return new DataResult<>() {}; }
                        }""",
                "com/mojang/serialization/MapCodec.java", """
                        package com.mojang.serialization;
                        public abstract class MapCodec<A> {
                            public abstract <T> java.util.stream.Stream<T> keys(DynamicOps<T> ops);
                            public abstract <T> DataResult<A> decode(DynamicOps<T> ops, MapLike<T> input);
                            public abstract <T> RecordBuilder<T> encode(A input, DynamicOps<T> ops, RecordBuilder<T> prefix);
                        }"""));
        sources.putAll(Map.of(
                "net/minecraft/world/item/crafting/RecipeSerializer.java", """
                        package net.minecraft.world.item.crafting;
                        public record RecipeSerializer(com.mojang.serialization.MapCodec codec,
                                net.minecraft.network.codec.StreamCodec streamCodec) {}""",
                "net/minecraft/network/codec/StreamCodec.java",
                "package net.minecraft.network.codec; public interface StreamCodec {}",
                "org/slf4j/Marker.java", "package org.slf4j; public interface Marker { String getName(); }",
                "org/slf4j/MarkerFactory.java", """
                        package org.slf4j;
                        public final class MarkerFactory {
                            public static Marker getMarker(String name) { return () -> name; }
                        }""",
                "org/slf4j/Logger.java", """
                        package org.slf4j;
                        public interface Logger {
                            default void debug(Marker marker, String message) { LoggerFactory.LINES.add("debug " + marker.getName() + " " + message); }
                            default void warn(String message) { LoggerFactory.LINES.add("warn " + message); }
                            default void warn(String message, Throwable cause) {
                                LoggerFactory.LINES.add("warn " + message + " (" + cause.getClass().getName() + ")");
                            }
                        }""",
                "org/slf4j/LoggerFactory.java", """
                        package org.slf4j;
                        public final class LoggerFactory {
                            public static final java.util.List<String> LINES = new java.util.ArrayList<>();
                            public static Logger getLogger(String name) { return new Logger() {}; }
                        }"""));
        return sources;
    }

    // A 1.20.1 serializer reads JSON itself; a broken one has a codec() that throws.
    private static Map<String, String> modSources() {
        return Map.of(
                "mod/JsonSerializer.java", """
                        package mod;
                        public class JsonSerializer {
                            public Object fromJson(Object id, Object json) { return null; }
                        }""",
                "mod/BrokenSerializer.java", """
                        package mod;
                        public class BrokenSerializer {
                            public Object codec() { throw new IllegalStateException("not built yet"); }
                            public Object streamCodec() { return null; }
                        }""");
    }
}
