/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** A mod that reads its registered serializer back gets its own object, though the registry holds an adapter. */
class RecipeSerializerCastBridgeTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @TempDir
    Path dir;

    @AfterEach
    void clear() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A cast or instanceof of a registry value to the mod's serializer class sees the mod's own object")
    void castReachesTheModObject() throws Throwable {
        transformer.registerSyntheticClass(LegacyRecipeSerializerShim.BRIDGE, LegacyRecipeSerializerShim.generateBridge());
        transformer.registerSyntheticClass(LegacyRecipeSerializerShim.SAFE_CODEC, LegacyRecipeSerializerShim.generateSafeCodec());
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("host"), LegacyRecipeSerializerLogTest.hostSources());
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(dir.resolve("mod"), modSources()), "mod/");
        byte[] reader = mod.get("mod/Reader");
        Map<String, byte[]> bridged = new HashMap<>(mod);
        bridged.put("mod/Reader", RecipeSerializerCastBridge.apply(reader, "mod/CodecSerializer"::equals));
        assertNotSame(reader, bridged.get("mod/Reader"), "the cast sites were rewritten");

        BridgeHarness.Run run = new BridgeHarness.Run(host, bridged);
        Object own = run.loadClass("mod.CodecSerializer").getConstructor().newInstance();
        Object adapted = run.call(LegacyRecipeSerializerShim.BRIDGE, "adapt", own);
        assertNotSame(own, adapted, "the registry holds the host's serializer");
        Supplier<Object> entry = () -> adapted;
        assertSame(own, run.call("mod/Reader", "read", entry));
        assertEquals(Boolean.TRUE, run.call("mod/Reader", "isOwn", entry));
        Supplier<Object> other = () -> "plain";
        assertEquals(Boolean.FALSE, run.call("mod/Reader", "isOwn", other), "other values pass through unchanged");
    }

    @Test
    @DisplayName("A cast of a registry value to a mod type that is not a recipe serializer stays as written")
    void otherModTypesStay() throws Exception {
        byte[] reader = BridgeHarness.only(BridgeHarness.compile(dir.resolve("mod"), modSources()), "mod/").get("mod/Reader");
        assertSame(reader, RecipeSerializerCastBridge.apply(reader, type -> false));
    }

    @Test
    @DisplayName("Casts to game, JDK and loader types, and casts of values not read from a generic getter, stay as written")
    void otherCastsStay() {
        assertFalse(RecipeSerializerCastBridge.modType("net/minecraft/world/item/crafting/RecipeSerializer"));
        assertFalse(RecipeSerializerCastBridge.modType("java/lang/String"));
        assertFalse(RecipeSerializerCastBridge.modType("[Lmod/CodecSerializer;"));
        assertTrue(RecipeSerializerCastBridge.modType("com/teamresourceful/resourcefullib/common/recipe/CodecRecipeSerializer"));
        // Outside a jar pass, a library's serializer is known by its name, and other library types are left alone.
        assertTrue(RecipeSerializerCastBridge.serializerType("com/teamresourceful/resourcefullib/common/recipe/CodecRecipeSerializer"));
        assertFalse(RecipeSerializerCastBridge.serializerType("com/teamresourceful/resourcefullib/common/registry/ResourcefulRegistry"));
    }

    private static Map<String, String> modSources() {
        return Map.of(
                "mod/CodecSerializer.java", """
                        package mod;
                        public class CodecSerializer {
                            public Object codec() { return null; }
                            public Object streamCodec() { return null; }
                        }""",
                "mod/Reader.java", """
                        package mod;
                        public class Reader {
                            public static Object read(java.util.function.Supplier<Object> entry) { return (CodecSerializer) entry.get(); }
                            public static boolean isOwn(java.util.function.Supplier<Object> entry) { return entry.get() instanceof CodecSerializer; }
                        }""");
    }
}
