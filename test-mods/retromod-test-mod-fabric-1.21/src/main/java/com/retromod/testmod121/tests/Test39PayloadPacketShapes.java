/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121.tests;

import com.retromod.testmod121.Test;
import com.retromod.testmod121.TestResult;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** The 1.20.5 to 1.21.11 payload shapes of the server networking API. */
public final class Test39PayloadPacketShapes implements Test {

    @Override
    public String description() {
        return "1.21 payload packets build, and old send callbacks run on the host sender";
    }

    @Override
    public TestResult run() {
        // createS2CPacket, which 26.1 renamed.
        Packet<?> packet = ServerPlayNetworking.createS2CPacket(new Ping121(8));
        if (packet == null) return TestResult.fail("createS2CPacket returned null");

        // An old PacketCallbacks, which 26.x replaced with netty's listener. Cardinal Components
        // sends its syncs this way.
        List<Object> sent = new ArrayList<>();
        PacketSender sender = (PacketSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PacketSender.class}, capture(sent));
        boolean[] ran = new boolean[2];
        Ping121 payload = new Ping121(9);
        sender.sendPacket(payload, PacketCallbacks.always(() -> ran[0] = true));
        sender.sendPacket(packet, PacketCallbacks.always(() -> ran[1] = true));
        if (sent.size() != 2 || sent.get(0) != payload || sent.get(1) != packet) {
            return TestResult.fail("the sender got " + sent);
        }
        if (!ran[0] || !ran[1]) return TestResult.fail("an old callback did not run: " + ran[0] + ", " + ran[1]);
        return TestResult.success();
    }

    /** Records each send and completes its listener as if the packet left. */
    private static InvocationHandler capture(List<Object> sent) {
        return (proxy, method, args) -> {
            if (method.getName().equals("sendPacket")) {
                sent.add(args[0]);
                if (args.length == 2 && args[1] != null) {
                    Class<?> listener = Class.forName("io.netty.util.concurrent.GenericFutureListener");
                    listener.getMethod("operationComplete", Class.forName("io.netty.util.concurrent.Future"))
                            .invoke(args[1], new EmbeddedChannel().newSucceededFuture());
                }
                return null;
            }
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
        };
    }
}
