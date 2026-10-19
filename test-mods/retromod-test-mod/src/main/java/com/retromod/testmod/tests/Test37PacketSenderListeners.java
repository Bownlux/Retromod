/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.util.Identifier;

/** Every old {@code PacketSender} send, with no listener, an old one and a netty one. */
public final class Test37PacketSenderListeners implements Test {

    private static final Identifier CHANNEL = new Identifier("retromod_test", "n1_sender");

    @Override
    public String description() {
        return "old PacketSender sends and listeners reach the host sender";
    }

    @Override
    public TestResult run() {
        // The receiver makes the channel's payload type exist, as a mod's own receiver would.
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL, (a, b, c, d, e) -> { });
        N1Loopback.Capture capture = new N1Loopback.Capture();
        PacketSender sender = capture.sender;
        int[] ran = new int[6];
        try {
            sender.sendPacket(CHANNEL, N1Loopback.buf(1));
            sender.sendPacket(CHANNEL, N1Loopback.buf(2), PacketCallbacks.always(() -> ran[0]++));
            sender.sendPacket(CHANNEL, N1Loopback.buf(3), future -> ran[1]++);
            Packet<?> packet = sender.createPacket(CHANNEL, N1Loopback.buf(4));
            sender.sendPacket(packet, future -> ran[2]++);
            sender.sendPacket(packet, PacketCallbacks.always(() -> ran[3]++));
            sender.sendPacket(new N1Packet(5));
            sender.sendPacket(new N1Packet(6), PacketCallbacks.always(() -> ran[4]++));
            sender.sendPacket(new N1Packet(7), future -> ran[5]++);
            if (N1Loopback.readInt(capture.lastBytes(N1Packet.TYPE.getId())) != 7) {
                return TestResult.fail("the last typed packet did not encode as 7");
            }
        } catch (Exception e) {
            return TestResult.fail("send failed: " + e);
        }
        if (capture.sent.size() != 8) return TestResult.fail("sent " + capture.sent.size() + " of 8");
        // 26.x takes only netty's ChannelFutureListener, so each old listener must still run once.
        for (int i = 0; i < ran.length; i++) {
            if (ran[i] != 1) return TestResult.fail("listener " + i + " ran " + ran[i] + " times");
        }
        return TestResult.success();
    }
}
