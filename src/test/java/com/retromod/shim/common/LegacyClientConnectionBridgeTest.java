/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.Forge_1_20_to_NeoForge_1_21;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipFile;

import static com.retromod.shim.common.BridgeHarness.*;
import static com.retromod.shim.common.LegacyClientConnectionBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** JEI's join-time reads: the server brand on the player (gone since 1.20.2) and the connection's id (gone in 26.1). */
class LegacyClientConnectionBridgeTest {

    private static final String MOD = "mod/ServerCheck";
    private static final String PROBE = "testhost/Probe";
    private static final String LIBRARIES = System.getProperty("user.home") + "/Library/Application Support/PrismLauncher/libraries/";
    private static final String GET_ID = PACKET_LISTENER + ".getId()Ljava/util/UUID;";
    private static final String REBUILT_ID = REBUILT + ".id(L" + PACKET_LISTENER + ";)Ljava/util/UUID;";
    private static final String REBUILT_BRAND = REBUILT + ".serverBrand(L" + LOCAL_PLAYER + ";)Ljava/lang/String;";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    private static Map<String, byte[]> host26;
    private static Map<String, byte[]> host12111;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        Map<String, String> old = new HashMap<>();
        old.put("net/minecraft/client/multiplayer/ClientPacketListener.java", """
                package net.minecraft.client.multiplayer;
                public class ClientPacketListener { public java.util.UUID getId() { return null; } }""");
        old.put("net/minecraft/client/player/LocalPlayer.java", """
                package net.minecraft.client.player;
                public class LocalPlayer { public String getServerBrand() { return null; } }""");
        // Like JEI's ClientConnectionHelper and ConnectionToServer.
        old.put("mod/ServerCheck.java", """
                package mod;
                public class ServerCheck {
                    public static String brand(Object player) { return ((net.minecraft.client.player.LocalPlayer) player).getServerBrand(); }
                    public static Object id(Object connection) { return ((net.minecraft.client.multiplayer.ClientPacketListener) connection).getId(); }
                }""");
        mod = only(compile(dir.resolve("old"), old), "mod/");
        host26 = compile(dir.resolve("host26"), hostSources(false));
        host12111 = compile(dir.resolve("host12111"), hostSources(true));
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("On a 26.x host the old reads fail as written and answer from the connection once bridged")
    void readsAnswerFromTheConnection() throws Throwable {
        Run old = new Run(host26, mod);
        Object player = old.call(PROBE, "player", "forge");
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class, () -> old.call(MOD, "brand", player)));

        Run game = new Run(host26, transform("26.2", bothOn(host26), mod));
        Object joined = game.call(PROBE, "player", "forge");
        assertEquals("forge", game.call(MOD, "brand", joined), "the brand comes from the player's connection");
        Object connection = game.call(PROBE, "connection", joined);
        Object id = game.call(MOD, "id", connection);
        assertNotNull(id, "a connection has an id");
        assertEquals(id, game.call(MOD, "id", connection), "the same connection keeps its id");
        Object other = game.call(PROBE, "connection", game.call(PROBE, "player", "vanilla"));
        assertNotEquals(id, game.call(MOD, "id", other), "each connection has its own id");
    }

    @Test
    @DisplayName("On a 1.21.11 host the brand is bridged and getId() keeps reaching the host's own id")
    void hostThatKeepsGetId() throws Throwable {
        Map<String, byte[]> out = transform("1.21.11", bothOn(host12111), mod);
        assertTrue(calls(out.get(MOD), "brand").contains(REBUILT_BRAND));
        assertTrue(calls(out.get(MOD), "id").contains(GET_ID), "the host's own getId() stays");

        Run game = new Run(host12111, out);
        Object player = game.call(PROBE, "player", "neoforge");
        assertEquals("neoforge", game.call(MOD, "brand", player));
        Object connection = game.call(PROBE, "connection", player);
        assertEquals(game.call(PROBE, "hostId", connection), game.call(MOD, "id", connection),
                "a mod reads the same id as the game");
    }

    @Test
    @DisplayName("The 1.21 layer bridges only the brand; the 26.1 class moves bridge the connection id")
    void layers() {
        Map<String, byte[]> at121 = transform("1.21.11", t -> new Forge_1_20_to_NeoForge_1_21().registerRedirects(t), mod);
        assertTrue(calls(at121.get(MOD), "brand").contains(REBUILT_BRAND));
        assertTrue(calls(at121.get(MOD), "id").contains(GET_ID), "1.21 to 1.21.11 hosts still have getId()");

        Map<String, byte[]> at26 = transform("26.2", Common_1_21_11_to_26_1_ClassMoves::register, mod);
        assertTrue(calls(at26.get(MOD), "id").contains(REBUILT_ID));
    }

    @Test
    @DisplayName("Each gate reads its own member")
    void gates() {
        ClassNode moved = withMethod(COMMON_LISTENER, "serverBrand", "()Ljava/lang/String;");
        ClassNode player = withMethod(LOCAL_PLAYER, "jumpableVehicle", "()Ljava/lang/String;");
        assertTrue(hostMovedServerBrand(name -> name.equals(LOCAL_PLAYER) ? player : name.equals(COMMON_LISTENER) ? moved : null),
                "1.20.2 and newer");
        ClassNode oldPlayer = withMethod(LOCAL_PLAYER, "getServerBrand", "()Ljava/lang/String;");
        assertFalse(hostMovedServerBrand(name -> name.equals(LOCAL_PLAYER) ? oldPlayer : null), "1.20.1 keeps its own");
        assertTrue(hostMovedServerBrand(name -> null), "a server or an offline run: the shim chain stands for the host");

        ClassNode keepsId = withMethod(PACKET_LISTENER, "getId", GET_ID_DESC);
        assertFalse(hostDroppedConnectionId(name -> name.equals(PACKET_LISTENER) ? keepsId : null), "1.21.11 keeps getId()");
        assertTrue(hostDroppedConnectionId(name -> name.equals(PACKET_LISTENER) ? new ClassNode() : null), "26.x");
        assertTrue(hostDroppedConnectionId(name -> null), "a server or an offline run: the 26.1 layer stands for the host");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "net/neoforged/minecraft-client-patched/21.11.42/minecraft-client-patched-21.11.42.jar, false",
            "net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar, true",
            "net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar, true",
            "com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar, true",
            "com/mojang/minecraft/26.3/minecraft-26.3-client.jar, true"})
    @DisplayName("Real client jars: every one moved the brand, and only 26.x dropped getId()")
    void realHosts(String jar, boolean droppedId) throws Exception {
        Path file = Path.of(LIBRARIES + jar);
        Assumptions.assumeTrue(Files.isRegularFile(file), "no " + file);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Function<String, ClassNode> read = name -> {
                try {
                    var entry = zip.getEntry(name + ".class");
                    if (entry == null) return null;
                    ClassNode node = new ClassNode();
                    new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
                    return node;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            assertTrue(hostMovedServerBrand(read), jar);
            assertEquals(droppedId, hostDroppedConnectionId(read), jar);
        }
    }

    // Both registrations, reading the given compiled host the way the game's class loader would.
    private static Consumer<RetromodTransformer> bothOn(Map<String, byte[]> host) {
        Function<String, ClassNode> read = name -> {
            byte[] bytes = host.get(name);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        };
        return t -> {
            registerServerBrand(t, read);
            registerConnectionId(t, read);
        };
    }

    private static ClassNode withMethod(String owner, String name, String desc) {
        ClassNode node = new ClassNode();
        node.name = owner;
        node.methods.add(new MethodNode(0x0001, name, desc, null, null));
        return node;
    }

    // The brand lives on the common listener and the player holds its connection; 1.21.11 also keeps getId().
    private static Map<String, String> hostSources(boolean keepsId) {
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl.java", """
                package net.minecraft.client.multiplayer;
                public abstract class ClientCommonPacketListenerImpl {
                    protected String serverBrand;
                    public String serverBrand() { return serverBrand; }
                }""");
        sources.put("net/minecraft/client/multiplayer/ClientPacketListener.java", """
                package net.minecraft.client.multiplayer;
                public class ClientPacketListener extends ClientCommonPacketListenerImpl {
                    private final java.util.UUID id = java.util.UUID.randomUUID();
                    public ClientPacketListener(String brand) { serverBrand = brand; }
                    %s
                }""".formatted(keepsId ? "public java.util.UUID getId() { return id; }" : ""));
        sources.put("net/minecraft/client/player/LocalPlayer.java", """
                package net.minecraft.client.player;
                public class LocalPlayer {
                    public final net.minecraft.client.multiplayer.ClientPacketListener connection;
                    public LocalPlayer(net.minecraft.client.multiplayer.ClientPacketListener connection) { this.connection = connection; }
                }""");
        sources.put("testhost/Probe.java", """
                package testhost;
                public class Probe {
                    public static Object player(String brand) {
                        return new net.minecraft.client.player.LocalPlayer(new net.minecraft.client.multiplayer.ClientPacketListener(brand));
                    }
                    public static Object connection(Object player) { return ((net.minecraft.client.player.LocalPlayer) player).connection; }
                    public static Object hostId(Object connection) throws Exception {
                        return connection.getClass().getMethod("getId").invoke(connection);
                    }
                }""");
        return sources;
    }
}
