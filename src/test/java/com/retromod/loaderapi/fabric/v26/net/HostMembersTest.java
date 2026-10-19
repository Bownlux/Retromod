/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net;

import com.retromod.loaderapi.fabric.v26.HostLink.Member;
import com.retromod.loaderapi.fabric.v26.HostSurface;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Every host member the networking helpers call exists on each 26.x host they serve. */
class HostMembersTest {

    private static final String SENDER = HostNet.SENDER;
    private static final String PAYLOAD = "L" + HostNet.PAYLOAD + ";";
    private static final String PACKET = "L" + HostNet.PACKET + ";";
    private static final String LISTENER = "L" + HostNet.CHANNEL_FUTURE_LISTENER + ";";
    private static final String RAW = "(L" + HostNet.IDENTIFIER + ";L" + HostNet.FRIENDLY_BUF + ";";

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3", "26.4"})
    @DisplayName("The Fabric API members the helpers call are on every indexed host")
    void fabricMembersExist(String line) {
        HostSurface surface = HostSurface.forLine(line);
        List<String> missing = new ArrayList<>();
        for (Member member : fabricMembers()) {
            if (surface.member(member.owner(), member.name(), member.desc()) == HostSurface.Presence.MISSING) {
                missing.add(member.owner() + "." + member.name() + member.desc());
            }
        }
        for (Endpoint endpoint : Endpoint.values()) {
            if (!surface.hasClass(endpoint.handler)) missing.add(endpoint.handler);
        }
        assertEquals(List.of(), missing, "on " + line);
    }

    @ParameterizedTest(name = "in {0}")
    @ValueSource(strings = {"minecraft-26.1.2-client.jar", "minecraft-26.3-client.jar"})
    @DisplayName("The Minecraft members the helpers call are in the local client jars")
    void minecraftMembersExist(String jarName) throws Exception {
        Path jar = Path.of("test-jars-mixin", jarName);
        assumeTrue(Files.exists(jar), jar + " is local test material and is not checked in");
        List<String> missing = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Member member : minecraftMembers()) {
                var entry = zip.getEntry(member.owner() + ".class");
                if (entry == null) {
                    missing.add(member.owner());
                    continue;
                }
                ClassNode node = new ClassNode();
                new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
                boolean found = member.desc().startsWith("(")
                        ? node.methods.stream().anyMatch(m -> m.name.equals(member.name()) && m.desc.equals(member.desc()))
                        : node.fields.stream().anyMatch(f -> f.name.equals(member.name()) && f.desc.equals(member.desc()));
                if (!found) missing.add(member.owner() + "." + member.name() + member.desc());
            }
        }
        assertEquals(List.of(), missing, "in " + jarName);
    }

    @Test
    @DisplayName("Each side receives the direction the other side sends")
    void directionsPair() {
        assertEquals(Endpoint.SERVER_PLAY.sends(), Endpoint.CLIENT_PLAY.receives());
        assertEquals(Endpoint.CLIENT_PLAY.sends(), Endpoint.SERVER_PLAY.receives());
        assertEquals(Endpoint.SERVER_CONFIGURATION.sends(), Endpoint.CLIENT_CONFIGURATION.receives());
    }

    private static List<Member> fabricMembers() {
        List<Member> members = new ArrayList<>();
        for (Endpoint endpoint : Endpoint.values()) {
            members.add(endpoint.registerGlobal());
            members.add(endpoint.registerOnConnection());
            members.add(endpoint.unregisterGlobal());
            members.add(endpoint.unregisterOnConnection());
            members.add(endpoint.send());
            members.add(endpoint.canSend(false));
            members.add(endpoint.context("responseSender", SENDER));
            if (endpoint != Endpoint.CLIENT_CONFIGURATION) members.add(endpoint.createPacket());
        }
        members.add(Endpoint.SERVER_PLAY.canSend(true));
        members.add(Endpoint.SERVER_PLAY.context("player", HostNet.SERVER_PLAYER));
        members.add(Endpoint.SERVER_PLAY.context("server", HostNet.MINECRAFT_SERVER));
        members.add(Endpoint.CLIENT_PLAY.context("client", HostNet.MINECRAFT));
        members.add(Endpoint.CLIENT_PLAY.context("player", "net/minecraft/client/player/LocalPlayer"));
        members.add(Endpoint.SERVER_CONFIGURATION.context("packetListener", HostNet.SERVER_CONFIG_LISTENER));
        members.add(Member.virtual(SENDER, "sendPacket", "(" + PACKET + ")V"));
        members.add(Member.virtual(SENDER, "sendPacket", "(" + PACKET + LISTENER + ")V"));
        members.add(Member.virtual(SENDER, "sendPacket", "(" + PAYLOAD + ")V"));
        members.add(Member.virtual(SENDER, "sendPacket", "(" + PAYLOAD + LISTENER + ")V"));
        members.add(Member.virtual(SENDER, "createPacket", "(" + PAYLOAD + ")" + PACKET));
        members.add(Member.virtual(HostNet.LOGIN_SENDER, "sendPacket", RAW + ")V"));
        members.add(Member.virtual(HostNet.LOGIN_SENDER, "sendPacket", RAW + LISTENER + ")V"));
        members.add(Member.virtual(HostNet.LOGIN_SENDER, "createPacket", RAW + ")" + PACKET));
        for (String registry : List.of("clientboundPlay", "serverboundPlay", "clientboundConfiguration",
                "serverboundConfiguration")) {
            members.add(Member.staticMethod(HostNet.REGISTRY, registry, "()L" + HostNet.REGISTRY + ";"));
        }
        members.add(Member.virtual(HostNet.REGISTRY, "register", "(L" + HostNet.PAYLOAD_TYPE + ";L"
                + HostNet.STREAM_CODEC + ";)L" + HostNet.PAYLOAD + "$TypeAndCodec;"));
        return members;
    }

    private static List<Member> minecraftMembers() {
        return List.of(
                Member.constructor(HostNet.PAYLOAD_TYPE, "(L" + HostNet.IDENTIFIER + ";)V"),
                Member.constructor(HostNet.FRIENDLY_BUF, "(L" + HostNet.BYTE_BUF + ";)V"),
                Member.virtual(HostNet.GAME_LISTENER, "getPlayer", "()L" + HostNet.SERVER_PLAYER + ";"),
                Member.virtual(HostNet.SERVER_PLAYER, "level", "()L" + HostNet.SERVER_LEVEL + ";"),
                Member.virtual(HostNet.SERVER_PLAYER, "connection", "L" + HostNet.GAME_LISTENER + ";"),
                Member.virtual(HostNet.SERVER_LEVEL, "getServer", "()L" + HostNet.MINECRAFT_SERVER + ";"),
                Member.virtual(HostNet.MINECRAFT, "getConnection", "()L" + HostNet.CLIENT_LISTENER + ";"),
                Member.constructor(HostNet.SEND_LISTENER, "()V"),
                Member.staticMethod(HostNet.IDENTIFIER, "fromNamespaceAndPath",
                        "(Ljava/lang/String;Ljava/lang/String;)L" + HostNet.IDENTIFIER + ";"),
                Member.constructor(HostNet.DISCARDED_PAYLOAD, "(L" + HostNet.IDENTIFIER + ";)V"),
                Member.staticMethod(HostNet.SEND_LISTENER, "thenRun", "(Ljava/lang/Runnable;)" + LISTENER),
                Member.staticMethod(HostNet.SEND_LISTENER, "exceptionallySend",
                        "(Ljava/util/function/Supplier;)" + LISTENER));
    }
}
