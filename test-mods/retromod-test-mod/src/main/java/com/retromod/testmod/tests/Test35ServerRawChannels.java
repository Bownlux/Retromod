/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/** Old raw play channels on the server, the shape 34 mods send with and 30 receive with. */
public final class Test35ServerRawChannels implements Test {

    static final Identifier ECHO = new Identifier("retromod_test", "n1_echo");
    static final Identifier REPLY = new Identifier("retromod_test", "n1_reply");
    private static final Identifier OVERRIDE = new Identifier("retromod_test", "n1_override");
    private static final Identifier CONNECTION = new Identifier("retromod_test", "n1_connection");
    private static final Identifier SENT = new Identifier("retromod_test", "n1_sent");

    private final MinecraftServer server;

    public Test35ServerRawChannels(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String description() {
        return "old raw play channels register, receive, answer and unregister on the server";
    }

    @Override
    public TestResult run() {
        int[] got = {-1};
        MinecraftServer[] seen = {null};
        if (!ServerPlayNetworking.registerGlobalReceiver(ECHO, (srv, player, handler, buf, responseSender) -> {
            got[0] = buf.readInt();
            seen[0] = srv;
            // The answer travels back to whichever side sent the packet.
            responseSender.sendPacket(REPLY, N1Loopback.buf(got[0] + 1));
        })) {
            return TestResult.fail("the first receiver on a channel was refused");
        }
        if (ServerPlayNetworking.registerGlobalReceiver(ECHO, (a, b, c, d, e) -> { })) {
            return TestResult.fail("a second receiver on one channel was accepted");
        }
        EchoOverride override = new EchoOverride();
        if (!ServerPlayNetworking.registerGlobalReceiver(OVERRIDE, override)) {
            return TestResult.fail("a receiver class was refused");
        }

        // A fake player has a real connection but claims no client channel until the test says so.
        FakePlayer player = FakePlayer.get(server.getOverworld());
        byte[] answer;
        try {
            answer = N1Loopback.receive(server, player, ECHO, N1Loopback.intBytes(20)).lastBytes(REPLY);
        } catch (Exception e) {
            return TestResult.fail("loopback failed: " + e);
        }
        if (got[0] != 20) return TestResult.fail("the receiver read " + got[0] + ", not 20");
        if (seen[0] != server) return TestResult.fail("the receiver got another server");
        if (N1Loopback.readInt(answer) != 21) return TestResult.fail("the answer encoded as " + N1Loopback.readInt(answer));

        if (ServerPlayNetworking.createS2CPacket(REPLY, N1Loopback.buf(1)) == null) {
            return TestResult.fail("createS2CPacket returned null");
        }
        ServerPlayNetworkHandler connection = player.networkHandler;
        if (ServerPlayNetworking.getServer(connection) != server) {
            return TestResult.fail("getServer did not return this server");
        }
        if (ServerPlayNetworking.canSend(player, ECHO)) {
            return TestResult.fail("a fake player claims a client channel");
        }
        // A send the client never registered is dropped, as its packet would be discarded there.
        ServerPlayNetworking.send(player, ECHO, N1Loopback.buf(3));
        // Once the connection claims a channel, the send runs the host's own send with the payload.
        try {
            N1Loopback.markSendable(connection, SENT);
        } catch (Exception e) {
            return TestResult.fail("could not make the fake player's connection claim a channel: " + e);
        }
        if (!ServerPlayNetworking.canSend(player, SENT)) {
            return TestResult.fail("canSend ignored a channel the connection claims");
        }
        ServerPlayNetworking.send(player, SENT, N1Loopback.buf(4));
        ServerPlayNetworking.PlayChannelHandler perConnection = (a, b, c, d, e) -> { };
        if (!ServerPlayNetworking.registerReceiver(connection, CONNECTION, perConnection)) {
            return TestResult.fail("a per-connection receiver was refused");
        }
        if (ServerPlayNetworking.unregisterReceiver(connection, CONNECTION) != perConnection) {
            return TestResult.fail("unregisterReceiver did not return the old handler");
        }
        if (ServerPlayNetworking.unregisterGlobalReceiver(OVERRIDE) != override) {
            return TestResult.fail("unregisterGlobalReceiver did not return the old handler");
        }
        return TestResult.success();
    }

    /** The override shape of the old handler. */
    private static final class EchoOverride implements ServerPlayNetworking.PlayChannelHandler {
        @Override
        public void receive(MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler,
                PacketByteBuf buf, PacketSender responseSender) {
        }
    }
}
