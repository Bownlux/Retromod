/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The rebuilt setFlammable, run against a stand-in of Fabric API's flammable registry that records what it is given. */
class LegacyFlammablesRuntimeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String HELPER = "com/retromod/legacyapi/minecraft/world/level/block/LegacyFlammables";

    @Test
    @DisplayName("setFlammable(fire, block, 5, 20) reaches the registry as add(block, 5, 20), ignite odds first")
    void keepsTheOldArgumentOrder(@TempDir Path dir) throws Exception {
        ClassLoader game = gameWith(BridgeHarness.compile(dir, HOST));
        Class<?> block = game.loadClass("net.minecraft.world.level.block.Block");
        Class<?> fire = game.loadClass("net.minecraft.world.level.block.FireBlock");
        Object fireBlock = fire.getConstructor().newInstance();
        Object planks = block.getConstructor().newInstance();

        game.loadClass(HELPER.replace('/', '.')).getMethod("setFlammable", fire, block, int.class, int.class)
                .invoke(null, fireBlock, planks, 5, 20);

        Object calls = game.loadClass("net.fabricmc.fabric.api.registry.FlammableBlockRegistry").getField("CALLS").get(null);
        // Vanilla's setFlammable and Fabric API's add both store ignite odds first and burn odds second.
        assertEquals(List.of(fireBlock, planks, 5, 20), calls, "getInstance(fire), then add(block, ignite, burn)");
    }

    /** A loader that defines the stand-ins and the rebuilt helper, and leaves Retromod's log to the test classpath. */
    private static ClassLoader gameWith(Map<String, byte[]> host) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(host);
        classes.put(HELPER, Files.readAllBytes(COMPILED.resolve(HELPER + ".class")));
        return new ClassLoader(LegacyFlammablesRuntimeTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    if (bytes == null) return super.loadClass(name, resolve);
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
                }
            }
        };
    }

    private static final Map<String, String> HOST = Map.of(
            "net/minecraft/world/level/block/Block.java", """
                    package net.minecraft.world.level.block;
                    public class Block {}""",
            "net/minecraft/world/level/block/FireBlock.java", """
                    package net.minecraft.world.level.block;
                    public class FireBlock extends Block {}""",
            "net/fabricmc/fabric/api/registry/FlammableBlockRegistry.java", """
                    package net.fabricmc.fabric.api.registry;
                    import net.minecraft.world.level.block.Block;
                    public interface FlammableBlockRegistry {
                        java.util.List<Object> CALLS = new java.util.ArrayList<>();
                        static FlammableBlockRegistry getInstance(Block fire) {
                            CALLS.add(fire);
                            return new FlammableBlockRegistry() {};
                        }
                        default void add(Block block, int igniteOdds, int burnOdds) {
                            CALLS.add(block);
                            CALLS.add(igniteOdds);
                            CALLS.add(burnOdds);
                        }
                    }""");
}
