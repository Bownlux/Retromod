/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** TerraBlender reads the world seed through {@code server.getWorldData().worldGenOptions()}, which 26.1 moved. */
class LegacyWorldGenOptionsAdapterTest {

    @TempDir
    Path dir;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("the seed read through the level data comes from the server's world gen settings")
    void seedFromTheServer() throws Throwable {
        Map<String, byte[]> mod = only(compile(dir.resolve("old"), withHost(OLD_HOST)), "mod/");
        Map<String, byte[]> host = compile(dir.resolve("host"), NEW_HOST);
        Run unadapted = new Run(host, mod);
        Object server = unadapted.loadClass("net.minecraft.server.MinecraftServer").getConstructor(long.class).newInstance(1234L);
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class,
                () -> unadapted.call("mod/Seeds", "seed", server)), "26.1 dropped WorldData.worldGenOptions()");

        Map<String, byte[]> adapted = new HashMap<>();
        mod.forEach((name, bytes) -> adapted.put(name, LegacyWorldGenOptionsAdapter.apply(bytes)));
        Run run = new Run(host, adapted);
        Object fresh = run.loadClass("net.minecraft.server.MinecraftServer").getConstructor(long.class).newInstance(1234L);
        assertEquals(1234L, run.call("mod/Seeds", "seed", fresh));
        // A level data the mod kept in a local is not adjacent to the server, so it is left as written.
        assertTrue(calls(adapted.get("mod/Seeds"), "fromData").contains(
                "net/minecraft/world/level/storage/WorldData.worldGenOptions()Lnet/minecraft/world/level/levelgen/WorldOptions;"));
    }

    @Test
    @DisplayName("the adapter is on from 26.1, and a host whose level data keeps the options keeps the call")
    void gate() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertFalse(LegacyWorldGenOptionsAdapter.hostMovedOptions(null, null));
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        assertTrue(LegacyWorldGenOptionsAdapter.hostMovedOptions(null, null));
        ClassNode data = new ClassNode();
        data.methods.add(new MethodNode(0, "worldGenOptions", LegacyWorldGenOptionsAdapter.OPTIONS_DESC, null, null));
        ClassNode server = new ClassNode();
        server.methods.add(new MethodNode(0, "getWorldGenSettings",
                "()Lnet/minecraft/world/level/levelgen/WorldGenSettings;", null, null));
        assertFalse(LegacyWorldGenOptionsAdapter.hostMovedOptions(data, server), "1.21.11's level data still has them");
        assertTrue(LegacyWorldGenOptionsAdapter.hostMovedOptions(new ClassNode(), server));
    }

    private static Map<String, String> withHost(Map<String, String> host) {
        Map<String, String> sources = new HashMap<>(host);
        sources.put("mod/Seeds.java", """
                package mod;
                import net.minecraft.server.MinecraftServer;
                import net.minecraft.world.level.storage.WorldData;
                public class Seeds {
                    public static long seed(MinecraftServer server) { return server.getWorldData().worldGenOptions().seed(); }
                    public static long fromData(WorldData data) { return data.worldGenOptions().seed(); }
                }""");
        return sources;
    }

    private static final Map<String, String> OLD_HOST = Map.of(
            "net/minecraft/world/level/levelgen/WorldOptions.java", """
                    package net.minecraft.world.level.levelgen;
                    public class WorldOptions { public long seed() { return 0; } }""",
            "net/minecraft/world/level/storage/WorldData.java", """
                    package net.minecraft.world.level.storage;
                    public interface WorldData { net.minecraft.world.level.levelgen.WorldOptions worldGenOptions(); }""",
            "net/minecraft/server/MinecraftServer.java", """
                    package net.minecraft.server;
                    public class MinecraftServer { public net.minecraft.world.level.storage.WorldData getWorldData() { return null; } }""");

    private static final Map<String, String> NEW_HOST = Map.of(
            "net/minecraft/world/level/levelgen/WorldOptions.java", """
                    package net.minecraft.world.level.levelgen;
                    public class WorldOptions {
                        private final long seed;
                        public WorldOptions(long seed) { this.seed = seed; }
                        public long seed() { return seed; }
                    }""",
            "net/minecraft/world/level/levelgen/WorldGenSettings.java", """
                    package net.minecraft.world.level.levelgen;
                    public class WorldGenSettings {
                        private final WorldOptions options;
                        public WorldGenSettings(WorldOptions options) { this.options = options; }
                        public WorldOptions options() { return options; }
                    }""",
            "net/minecraft/world/level/storage/WorldData.java",
            "package net.minecraft.world.level.storage; public interface WorldData {}",
            "net/minecraft/server/MinecraftServer.java", """
                    package net.minecraft.server;
                    import net.minecraft.world.level.levelgen.*;
                    public class MinecraftServer {
                        private final WorldGenSettings settings;
                        public MinecraftServer(long seed) { settings = new WorldGenSettings(new WorldOptions(seed)); }
                        public net.minecraft.world.level.storage.WorldData getWorldData() { return new net.minecraft.world.level.storage.WorldData() {}; }
                        public WorldGenSettings getWorldGenSettings() { return settings; }
                    }""");
}
