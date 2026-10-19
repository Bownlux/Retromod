/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;

/** Old typed packets on the server: register, receive, answer and unregister. */
public final class Test36ServerTypedPackets implements Test {

    private final MinecraftServer server;

    public Test36ServerTypedPackets(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String description() {
        return "old typed packets register, read, answer and unregister on the server";
    }

    @Override
    public TestResult run() {
        int[] got = {-1};
        ServerPlayNetworking.PlayPacketHandler<N1Packet> handler = (packet, player, responseSender) -> {
            got[0] = packet.value();
            responseSender.sendPacket(new N1Packet(packet.value() + 1));
        };
        if (!ServerPlayNetworking.registerGlobalReceiver(N1Packet.TYPE, handler)) {
            return TestResult.fail("the typed receiver was refused");
        }
        FakePlayer player = FakePlayer.get(server.getOverworld());
        byte[] answer;
        try {
            answer = N1Loopback.receive(server, player, N1Packet.TYPE.getId(), N1Loopback.intBytes(30))
                    .lastBytes(N1Packet.TYPE.getId());
        } catch (Exception e) {
            return TestResult.fail("loopback failed: " + e);
        }
        if (got[0] != 30) return TestResult.fail("the receiver read " + got[0] + ", not 30");
        if (N1Loopback.readInt(answer) != 31) return TestResult.fail("the answer encoded as " + N1Loopback.readInt(answer));
        if (N1Packet.TYPE.read(N1Loopback.buf(5)).value() != 5) {
            return TestResult.fail("PacketType.read did not read the packet back");
        }

        ServerPlayNetworkHandler connection = player.networkHandler;
        if (ServerPlayNetworking.canSend(player, N1Packet.TYPE) || ServerPlayNetworking.canSend(connection, N1Packet.TYPE)) {
            return TestResult.fail("a fake player claims a typed client channel");
        }
        ServerPlayNetworking.send(player, new N1Packet(1));
        // Once the connection claims the channel, the send runs the host's own send with the payload.
        try {
            N1Loopback.markSendable(connection, N1Packet.TYPE.getId());
        } catch (Exception e) {
            return TestResult.fail("could not make the fake player's connection claim a channel: " + e);
        }
        if (!ServerPlayNetworking.canSend(player, N1Packet.TYPE)) {
            return TestResult.fail("canSend ignored a typed channel the connection claims");
        }
        ServerPlayNetworking.send(player, new N1Packet(2));
        ServerPlayNetworking.PlayPacketHandler<N1Packet> perConnection = (packet, p, sender) -> { };
        if (!ServerPlayNetworking.registerReceiver(connection, N1Packet.TYPE, perConnection)) {
            return TestResult.fail("a per-connection typed receiver was refused");
        }
        if (ServerPlayNetworking.unregisterReceiver(connection, N1Packet.TYPE) != perConnection) {
            return TestResult.fail("unregisterReceiver did not return the typed handler");
        }
        if (ServerPlayNetworking.unregisterGlobalReceiver(N1Packet.TYPE) != handler) {
            return TestResult.fail("unregisterGlobalReceiver did not return the typed handler");
        }
        // Registered again, so a client that joins later still gets its echo.
        ServerPlayNetworking.registerGlobalReceiver(N1Packet.TYPE, handler);
        return TestResult.success();
    }
}
