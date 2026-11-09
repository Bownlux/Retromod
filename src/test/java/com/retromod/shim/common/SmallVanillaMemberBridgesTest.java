/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.fabric.Fabric_1_21_1_to_1_21_2;
import com.retromod.shim.fabric.Fabric_1_21_8_to_1_21_9;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_1_to_1_21_2;
import com.retromod.shim.forge.Forge_1_21_8_to_1_21_9;
import com.retromod.shim.neoforge.NeoForge_1_21_10_to_1_21_11;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_1_to_1_21_2;
import com.retromod.shim.neoforge.NeoForge_1_21_8_to_1_21_9;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Small vanilla members 1.21.1 mods still call: the chunk position and chat calls 26.1 removed,
 * the registry and block state calls 1.21.2 changed, and {@code getServer()}, which 1.21.9 removed.
 */
class SmallVanillaMemberBridgesTest {

    private static final String CALLS = "mod/Calls";
    private static final String CHUNKS = "mod/Chunks";
    private static final String SERVER_CALLS = "mod/ServerCalls";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    private static Map<String, byte[]> intermediaryMod;
    private static Map<String, byte[]> intermediaryChunks;
    private static Map<String, byte[]> host261;
    private static Map<String, byte[]> serverMod;
    private static Map<String, byte[]> intermediaryServerMod;
    private static Map<String, byte[]> serverHost;
    private static Map<String, byte[]> intermediaryServerHost;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        Map<String, String> old = new HashMap<>(Map.of(
                "net/minecraft/core/BlockPos.java", """
                        package net.minecraft.core;
                        public class BlockPos {
                            public BlockPos(int x, int y, int z) {}
                            public int getX() { return 0; }
                            public int getZ() { return 0; }
                        }""",
                "net/minecraft/world/level/ChunkPos.java", """
                        package net.minecraft.world.level;
                        public class ChunkPos {
                            public final int x, z;
                            public ChunkPos(net.minecraft.core.BlockPos pos) { x = 0; z = 0; }
                        }""",
                "net/minecraft/network/chat/Component.java",
                "package net.minecraft.network.chat; public interface Component {}",
                "net/minecraft/client/gui/components/ChatComponent.java", """
                        package net.minecraft.client.gui.components;
                        public class ChatComponent { public void addMessage(net.minecraft.network.chat.Component c) {} }""",
                "net/minecraft/world/entity/player/Player.java", """
                        package net.minecraft.world.entity.player;
                        public class Player { public void displayClientMessage(net.minecraft.network.chat.Component c, boolean bar) {} }""",
                "net/minecraft/server/level/ServerPlayer.java", """
                        package net.minecraft.server.level;
                        public class ServerPlayer extends net.minecraft.world.entity.player.Player {}""",
                "net/minecraft/resources/ResourceKey.java", "package net.minecraft.resources; public class ResourceKey<T> {}",
                "net/minecraft/core/RegistryAccess.java", """
                        package net.minecraft.core;
                        public interface RegistryAccess {
                            java.util.Optional<Object> registry(net.minecraft.resources.ResourceKey<?> key);
                            interface Frozen extends RegistryAccess {}
                        }""",
                "net/minecraft/world/level/BlockGetter.java", "package net.minecraft.world.level; public interface BlockGetter {}",
                "net/minecraft/world/level/block/state/BlockState.java", """
                        package net.minecraft.world.level.block.state;
                        public class BlockState {
                            public boolean isSolidRender(net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos) { return false; }
                        }"""));
        old.put("mod/Calls.java", """
                package mod;
                import net.minecraft.core.*;
                public class Calls {
                    public static void chat(net.minecraft.client.gui.components.ChatComponent chat, net.minecraft.network.chat.Component c) { chat.addMessage(c); }
                    public static void tell(net.minecraft.server.level.ServerPlayer p, net.minecraft.network.chat.Component c) { p.displayClientMessage(c, true); }
                    public static Object registry(RegistryAccess.Frozen access) { return access.registry(null).get(); }
                    public static boolean solid(net.minecraft.world.level.block.state.BlockState s) { return s.isSolidRender(null, null); }
                }""");
        old.put("mod/Chunks.java", """
                package mod;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.ChunkPos;
                public class Chunks {
                    public static String chunk(int x, int z) { ChunkPos c = new ChunkPos(new BlockPos(x, 64, z)); return c.x + "," + c.z; }
                    public static int sectionX(ChunkPos c) { return c.x; }
                }""");
        mod = only(compile(dir.resolve("old"), old), "mod/");
        intermediaryChunks = only(compile(dir.resolve("intermediary-chunks"), Map.of(
                "net/minecraft/class_2338.java", """
                        package net.minecraft;
                        public class class_2338 { public class_2338(int x, int y, int z) {} }""",
                "net/minecraft/class_1923.java", """
                        package net.minecraft;
                        public class class_1923 {
                            public final int field_9181, field_9180;
                            public class_1923(class_2338 pos) { field_9181 = 0; field_9180 = 0; }
                        }""",
                "mod/Chunks.java", """
                        package mod;
                        import net.minecraft.*;
                        public class Chunks {
                            public static String chunk(int x, int z) { class_1923 c = new class_1923(new class_2338(x, 64, z)); return c.field_9181 + "," + c.field_9180; }
                        }""")), "mod/");
        intermediaryMod = only(compile(dir.resolve("intermediary-old"), Map.of(
                "net/minecraft/class_5321.java", "package net.minecraft; public class class_5321 {}",
                "net/minecraft/class_5455.java", """
                        package net.minecraft;
                        public interface class_5455 {
                            java.util.Optional<Object> method_33310(class_5321 key);
                            interface class_6890 extends class_5455 {}
                        }""",
                "net/minecraft/class_1922.java", "package net.minecraft; public interface class_1922 {}",
                "net/minecraft/class_2338.java", "package net.minecraft; public class class_2338 {}",
                "net/minecraft/class_2680.java", """
                        package net.minecraft;
                        public class class_2680 { public boolean method_26216(class_1922 level, class_2338 pos) { return false; } }""",
                "mod/Calls.java", """
                        package mod;
                        import net.minecraft.*;
                        public class Calls {
                            public static Object registry(class_5455.class_6890 access) { return access.method_33310(null).get(); }
                            public static boolean solid(class_2680 s) { return s.method_26216(null, null); }
                        }""")), "mod/");
        host261 = compile(dir.resolve("host261"), Map.of(
                "net/minecraft/core/BlockPos.java", """
                        package net.minecraft.core;
                        public class BlockPos {
                            private final int x, z;
                            public BlockPos(int x, int y, int z) { this.x = x; this.z = z; }
                            public int getX() { return x; }
                            public int getZ() { return z; }
                        }""",
                // 26.1's shape: a record, so x and z are private fields behind x() and z().
                "net/minecraft/world/level/ChunkPos.java", """
                        package net.minecraft.world.level;
                        public record ChunkPos(int x, int z) {
                            public static ChunkPos containing(net.minecraft.core.BlockPos pos) { return new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4); }
                        }"""));

        String serverSource = """
                package mod;
                public class ServerCalls {
                    public static Object fromPlayer(%1$s p) { return p.%2$s(); }
                    public static Object fromServerPlayer(%3$s p) { return p.%2$s(); }
                    public static Object fromEntity(%4$s e) { return e.%2$s(); }
                }""";
        serverMod = only(compile(dir.resolve("server-old"), Map.of(
                "net/minecraft/server/MinecraftServer.java", "package net.minecraft.server; public class MinecraftServer {}",
                "net/minecraft/world/entity/Entity.java", """
                        package net.minecraft.world.entity;
                        public class Entity { public net.minecraft.server.MinecraftServer getServer() { return null; } }""",
                "net/minecraft/world/entity/player/Player.java", """
                        package net.minecraft.world.entity.player;
                        public class Player extends net.minecraft.world.entity.Entity {}""",
                "net/minecraft/server/level/ServerPlayer.java", """
                        package net.minecraft.server.level;
                        public class ServerPlayer extends net.minecraft.world.entity.player.Player {}""",
                "mod/ServerCalls.java", serverSource.formatted("net.minecraft.world.entity.player.Player", "getServer",
                        "net.minecraft.server.level.ServerPlayer", "net.minecraft.world.entity.Entity"))), "mod/");
        intermediaryServerMod = only(compile(dir.resolve("intermediary-server-old"), Map.of(
                "net/minecraft/server/MinecraftServer.java", "package net.minecraft.server; public class MinecraftServer {}",
                "net/minecraft/class_1297.java", """
                        package net.minecraft;
                        public class class_1297 { public net.minecraft.server.MinecraftServer method_5682() { return null; } }""",
                "net/minecraft/class_1657.java", "package net.minecraft; public class class_1657 extends class_1297 {}",
                "net/minecraft/class_3222.java", "package net.minecraft; public class class_3222 extends class_1657 {}",
                "mod/ServerCalls.java", serverSource.formatted("net.minecraft.class_1657", "method_5682",
                        "net.minecraft.class_3222", "net.minecraft.class_1297"))), "mod/");
        serverHost = serverHost(dir.resolve("server-host"), "net.minecraft.world.entity", "Entity", "level",
                "net.minecraft.world.level", "Level", "getServer", "net.minecraft.world.entity.player", "Player",
                "net.minecraft.server.level", "ServerPlayer");
        intermediaryServerHost = serverHost(dir.resolve("intermediary-server-host"), "net.minecraft", "class_1297",
                "method_73183", "net.minecraft", "class_1937", "method_8503", "net.minecraft", "class_1657",
                "net.minecraft", "class_3222");
    }

    /** 1.21.9's shape: no getServer() on entities, whose level has the server on a server and none on a client. */
    private static Map<String, byte[]> serverHost(Path at, String entityPkg, String entity, String level,
            String levelPkg, String levelClass, String getServer, String playerPkg, String player,
            String serverPlayerPkg, String serverPlayer) throws Exception {
        String levelName = levelPkg + "." + levelClass;
        return compile(at, Map.of(
                "net/minecraft/server/MinecraftServer.java", """
                        package net.minecraft.server;
                        public class MinecraftServer { public static final MinecraftServer RUNNING = new MinecraftServer(); }""",
                levelName.replace('.', '/') + ".java", "package " + levelPkg + "; public class " + levelClass
                        + " { public final boolean server; public " + levelClass + "(boolean server) { this.server = server; }"
                        + " public net.minecraft.server.MinecraftServer " + getServer
                        + "() { return server ? net.minecraft.server.MinecraftServer.RUNNING : null; } }",
                (entityPkg + "." + entity).replace('.', '/') + ".java", "package " + entityPkg + "; public class "
                        + entity + " { private final " + levelName + " level; public " + entity + "(boolean server) {"
                        + " level = new " + levelName + "(server); } public " + levelName + " " + level
                        + "() { return level; } }",
                (playerPkg + "." + player).replace('.', '/') + ".java", "package " + playerPkg + "; public class "
                        + player + " extends " + entityPkg + "." + entity + " { public " + player
                        + "(boolean server) { super(server); } }",
                (serverPlayerPkg + "." + serverPlayer).replace('.', '/') + ".java", "package " + serverPlayerPkg
                        + "; public class " + serverPlayer + " extends " + playerPkg + "." + player + " { public "
                        + serverPlayer + "() { super(true); } }"));
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("new ChunkPos(pos) and its x and z fields fail on 26.1 as written and work through containing(pos), x() and z()")
    void chunkPosFromBlockPos() throws Throwable {
        Run old = new Run(host261, mod);
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class, () -> old.call(CHUNKS, "chunk", 33, -17)));
        Object chunk = old.loadClass("net.minecraft.world.level.ChunkPos").getConstructor(int.class, int.class)
                .newInstance(2, -2);
        assertInstanceOf(IllegalAccessError.class, assertThrows(Throwable.class, () -> old.call(CHUNKS, "sectionX", chunk)),
                "the record's x field is private on 26.1");

        Run run = new Run(host261, transform("26.1", Common_1_21_11_to_26_1_ClassMoves::register, mod));
        assertEquals("2,-2", run.call(CHUNKS, "chunk", 33, -17), "the block's section coordinates, read as before");

        // A Fabric mod reaches the same rules once its intermediary ids are remapped on 26.x.
        Run fabric = new Run(host261, transform("26.1", t -> {
            IntermediaryToMojangMapper.applyTo(t);
            new Fabric_1_21_11_to_26_1().registerRedirects(t);
        }, intermediaryChunks));
        assertEquals("2,-2", fabric.call(CHUNKS, "chunk", 33, -17), "Fabric, from field_9181 and field_9180");
    }

    @Test
    @DisplayName("every 26.1 shim bridges the chunk, chat and server-message calls, and 1.21.11 keeps them")
    void calls261() {
        String chunk = "net/minecraft/world/level/ChunkPos.containing(Lnet/minecraft/core/BlockPos;)"
                + "Lnet/minecraft/world/level/ChunkPos;";
        String chat = "net/minecraft/client/gui/components/ChatComponent.addClientSystemMessage"
                + "(Lnet/minecraft/network/chat/Component;)V";
        String tell = LegacyClientInteractionSynthetic.INTERNAL + ".displayClientMessage"
                + "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/network/chat/Component;Z)V";
        for (VersionShim shim : List.of(new NeoForge_1_21_11_to_26_1(), new Forge_1_21_11_to_26_1(),
                new Fabric_1_21_11_to_26_1())) {
            Map<String, byte[]> out = transform("26.1", shim::registerRedirects, mod);
            assertTrue(calls(out.get(CHUNKS), "chunk").contains(chunk), shim.getShimName());
            assertEquals(List.of("net/minecraft/world/level/ChunkPos.x()I"), calls(out.get(CHUNKS), "sectionX"),
                    shim.getShimName());
            assertEquals(List.of(), fields(out.get(CHUNKS), "chunk"), shim.getShimName());
            assertEquals(List.of(chat), calls(out.get(CALLS), "chat"), shim.getShimName());
            assertEquals(List.of(tell), calls(out.get(CALLS), "tell"), shim.getShimName() + ", owner ServerPlayer");
        }
        Map<String, byte[]> out = transform("1.21.11", t -> new NeoForge_1_21_10_to_1_21_11().registerRedirects(t), mod);
        assertTrue(calls(out.get(CHUNKS), "chunk").contains("net/minecraft/world/level/ChunkPos.<init>(Lnet/minecraft/core/BlockPos;)V"));
        assertEquals(List.of("net/minecraft/world/level/ChunkPos.x:I", "net/minecraft/world/level/ChunkPos.z:I"),
                fields(out.get(CHUNKS), "chunk"), "1.21.11 still has the public fields");
        assertEquals(List.of("net/minecraft/client/gui/components/ChatComponent.addMessage(Lnet/minecraft/network/chat/Component;)V"),
                calls(out.get(CALLS), "chat"));
    }

    @Test
    @DisplayName("every 1.21.2 shim sends registry(key) to lookup(key) and drops isSolidRender's arguments")
    void calls1212() {
        String lookup = "net/minecraft/core/RegistryAccess$Frozen.lookup(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;";
        String solid = "net/minecraft/world/level/block/state/BlockState.isSolidRender()Z";
        // Fabric reaches the Mojang names on 26.x, where its 26.1 shim carries the registry rename.
        Map<String, Consumer<RetromodTransformer>> loaders = Map.of(
                "NeoForge", t -> new NeoForge_1_21_1_to_1_21_2().registerRedirects(t),
                "Forge", t -> new Forge_1_21_1_to_1_21_2().registerRedirects(t),
                "Fabric", t -> {
                    new Fabric_1_21_1_to_1_21_2().registerRedirects(t);
                    new Fabric_1_21_11_to_26_1().registerRedirects(t);
                });
        loaders.forEach((loader, register) -> {
            Map<String, byte[]> out = transform(loader.equals("Fabric") ? "26.3" : "1.21.4", register, mod);
            assertTrue(calls(out.get(CALLS), "registry").contains(lookup), loader);
            assertEquals(List.of(solid), calls(out.get(CALLS), "solid"), loader);
        });
        // A Fabric 1.21.2 to 1.21.11 host runs the same members under their intermediary ids.
        Map<String, byte[]> out = transform("1.21.4", t -> new Fabric_1_21_1_to_1_21_2().registerRedirects(t),
                intermediaryMod);
        assertTrue(calls(out.get(CALLS), "registry").contains(
                "net/minecraft/class_5455$class_6890.method_46759(Lnet/minecraft/class_5321;)Ljava/util/Optional;"));
        assertEquals(List.of("net/minecraft/class_2680.method_26216()Z"), calls(out.get(CALLS), "solid"));
        // 1.21.1 still has both, and a shim registers only on a host at or above its target.
        for (VersionShim shim : List.of(new NeoForge_1_21_1_to_1_21_2(), new Forge_1_21_1_to_1_21_2(),
                new Fabric_1_21_1_to_1_21_2())) {
            assertEquals("1.21.2", shim.getTargetVersion(), shim.getShimName());
        }
    }

    @Test
    @DisplayName("getServer() fails on 1.21.9 as written and reads the level's server once bridged, null on a client")
    void getServerFromTheLevel() throws Throwable {
        Run old = new Run(serverHost, serverMod);
        Object serverPlayer = old.loadClass("net.minecraft.server.level.ServerPlayer").getConstructor().newInstance();
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class,
                () -> old.call(SERVER_CALLS, "fromServerPlayer", serverPlayer)));

        assertServerReads(new Run(serverHost, transform("1.21.10",
                t -> new NeoForge_1_21_8_to_1_21_9().registerRedirects(t), serverMod)),
                "net.minecraft.server.level.ServerPlayer", "net.minecraft.world.entity.player.Player");
        assertServerReads(new Run(intermediaryServerHost, transform("1.21.10",
                t -> new Fabric_1_21_8_to_1_21_9().registerRedirects(t), intermediaryServerMod)),
                "net.minecraft.class_3222", "net.minecraft.class_1657");
    }

    @Test
    @DisplayName("Forge and Fabric on 26.x bridge getServer() too, and a 1.21.8 host keeps it")
    void getServerCoverageAndGate() {
        String bridged = LegacyPlayerServerBridge.MOJANG_GETTER.helper() + ".getServer"
                + LegacyPlayerServerBridge.MOJANG_GETTER.helperDesc();
        Map<VersionShim, String> shims = Map.of(new Forge_1_21_8_to_1_21_9(), "1.21.10",
                new Fabric_1_21_8_to_1_21_9(), "26.3");
        shims.forEach((shim, target) -> assertEquals(List.of(bridged),
                calls(transform(target, shim::registerRedirects, serverMod).get(SERVER_CALLS), "fromPlayer"),
                shim.getShimName()));
        assertEquals(List.of("net/minecraft/world/entity/player/Player.getServer()Lnet/minecraft/server/MinecraftServer;"),
                calls(transform("1.21.8", LegacyPlayerServerBridge::registerGetServer, serverMod).get(SERVER_CALLS),
                        "fromPlayer"));
    }

    private static void assertServerReads(Run run, String serverPlayer, String player) throws Throwable {
        Object running = run.loadClass("net.minecraft.server.MinecraftServer").getField("RUNNING").get(null);
        Object onServer = run.loadClass(serverPlayer).getConstructor().newInstance();
        Object onClient = run.loadClass(player).getConstructor(boolean.class).newInstance(false);
        assertSame(running, run.call(SERVER_CALLS, "fromServerPlayer", onServer));
        assertSame(running, run.call(SERVER_CALLS, "fromEntity", onServer), "the call through Entity");
        assertNull(run.call(SERVER_CALLS, "fromPlayer", onClient), "a client player has no server, as before");
    }
}
