/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.shim.forge.embedded.LegacyLootModifierCodecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** An old processor type whose codec cannot be read registers a map codec that fails with the reason, never the raw lambda. */
class LegacyProcessorTypeCodecTest {

    private static final Path LIBRARIES = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
    private static final Path DFU = LIBRARIES.resolve("com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar");
    private static final Path FASTUTIL = LIBRARIES.resolve("it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar");

    @TempDir
    Path dir;

    @Test
    @DisplayName("A type whose processor class fails to initialize becomes a map codec whose reads fail with the root cause")
    void unreadableTypeFailsWithItsReason() throws Exception {
        assumeTrue(Files.isRegularFile(DFU) && Files.isRegularFile(FASTUTIL),
                "no local DataFixerUpper 10 or fastutil jar; they are local test material");
        String gson = Path.of(com.google.gson.JsonObject.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        ClassLoader game = load(BridgeHarness.compile(dir, SOURCES, DFU + File.pathSeparator + gson));
        Object type = game.loadClass("mod.Types").getMethod("broken").invoke(null);

        Object codec = LegacyLootModifierCodecs.processorTypeCodec(type);

        Class<?> mapCodec = game.loadClass("com.mojang.serialization.MapCodec");
        assertTrue(mapCodec.isInstance(codec), "the registry gets a map codec, not the mod's lambda");
        Object ops = game.loadClass("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null);
        Object result = game.loadClass("mod.Types").getMethod("read", Object.class, Object.class).invoke(null, codec, ops);
        Optional<?> error = (Optional<?>) result.getClass().getMethod("error").invoke(result);
        assertTrue(error.isPresent(), "a read fails instead of throwing");
        String message = String.valueOf(error.get().getClass().getMethod("message").invoke(error.get()));
        assertTrue(message.contains("Could not read the codec of"), message);
        assertTrue(message.contains("IllegalStateException: no tool"), "it names the initializer's own failure: " + message);
    }

    @Test
    @DisplayName("Values that are not processor types are left to the caller")
    void otherValuesAreNotTypes() {
        assertNull(LegacyLootModifierCodecs.processorTypeCodec("not a type"));
        assertNull(LegacyLootModifierCodecs.processorTypeCodec(null));
    }

    private static ClassLoader load(Map<String, byte[]> classes) throws Exception {
        return new URLClassLoader(new URL[]{DFU.toUri().toURL(), FASTUTIL.toUri().toURL()}, LegacyProcessorTypeCodecTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded != null) return loaded;
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
                    if (name.startsWith("com.mojang.") || name.startsWith("it.unimi.")) return findClass(name);
                    return super.loadClass(name, resolve);
                }
            }
        };
    }

    // A processor type interface under its host name, and a type whose processor class throws while it initializes.
    private static final Map<String, String> SOURCES = Map.of(
            "net/minecraft/world/level/levelgen/structure/templatesystem/StructureProcessorType.java", """
                    package net.minecraft.world.level.levelgen.structure.templatesystem;
                    public interface StructureProcessorType<P> { Object codec(); }""",
            "mod/BrokenProcessor.java", """
                    package mod;
                    public class BrokenProcessor {
                        public static final Object CODEC = fail();
                        private static Object fail() { throw new IllegalStateException("no tool"); }
                    }""",
            "mod/Types.java", """
                    package mod;
                    import com.mojang.serialization.JsonOps;
                    import com.mojang.serialization.MapCodec;
                    import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
                    public class Types {
                        public static Object broken() { return (StructureProcessorType<Object>) () -> BrokenProcessor.CODEC; }
                        public static Object read(Object codec, Object ops) {
                            return ((MapCodec<?>) codec).codec().parse(JsonOps.INSTANCE, new com.google.gson.JsonObject());
                        }
                    }""");
}
