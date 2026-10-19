/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import com.retromod.testmod.TestPhases;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.util.Identifier;

import java.util.List;

/** The client half of the old channel echo, against a server that runs this mod. */
public final class Test38ClientChannelEcho implements Test {

    private static final Identifier CONNECTION = new Identifier("retromod_test", "n1_client_connection");
    private static final Identifier UNUSED = new Identifier("retromod_test", "n1_client_unused");
    private static final PacketType<N1Packet> CONNECTION_TYPE =
            PacketType.create(new Identifier("retromod_test", "n1_client_typed"), N1Packet::new);
    // The server cases answer each send with the value plus one. The result is logged once both
    // answers arrive, or after ten seconds.
    private static final int TIMEOUT_TICKS = 200;

    private static volatile int raw = -1;
    private static volatile int typed = -1;
    private static volatile String failure;
    private static int waited = -1;

    /** Registers the receivers at client start and sends at each join. */
    public static void install() {
        ClientPlayNetworking.registerGlobalReceiver(Test35ServerRawChannels.REPLY,
                (client, handler, buf, responseSender) -> raw = buf.readInt());
        ClientPlayNetworking.registerGlobalReceiver(N1Packet.TYPE,
                (packet, player, responseSender) -> typed = packet.value());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            raw = -1;
            typed = -1;
            failure = null;
            try {
                if (!ClientPlayNetworking.canSend(Test35ServerRawChannels.ECHO)) failure = "the server has no echo channel";
                if (!ClientPlayNetworking.canSend(N1Packet.TYPE)) failure = "the server has no typed channel";
                if (ClientPlayNetworking.createC2SPacket(Test35ServerRawChannels.ECHO, N1Loopback.buf(1)) == null) {
                    failure = "createC2SPacket returned null";
                }
                ClientPlayNetworking.PlayChannelHandler perConnection = (c, h, b, s) -> { };
                if (!ClientPlayNetworking.registerReceiver(CONNECTION, perConnection)
                        || ClientPlayNetworking.unregisterReceiver(CONNECTION) != perConnection) {
                    failure = "the per-connection receiver did not register and unregister";
                }
                ClientPlayNetworking.PlayPacketHandler<N1Packet> typedConnection = (p, pl, s) -> { };
                if (!ClientPlayNetworking.registerReceiver(CONNECTION_TYPE, typedConnection)
                        || ClientPlayNetworking.unregisterReceiver(CONNECTION_TYPE) != typedConnection) {
                    failure = "the per-connection typed receiver did not register and unregister";
                }
                ClientPlayNetworking.PlayChannelHandler unused = (c, h, b, s) -> { };
                ClientPlayNetworking.PlayPacketHandler<N1Packet> unusedTyped = (p, pl, s) -> { };
                ClientPlayNetworking.registerGlobalReceiver(UNUSED, unused);
                ClientPlayNetworking.registerGlobalReceiver(CONNECTION_TYPE, unusedTyped);
                if (ClientPlayNetworking.unregisterGlobalReceiver(UNUSED) != unused
                        || ClientPlayNetworking.unregisterGlobalReceiver(CONNECTION_TYPE) != unusedTyped) {
                    failure = "unregisterGlobalReceiver did not return the old handlers";
                }
                ClientPlayNetworking.send(Test35ServerRawChannels.ECHO, N1Loopback.buf(50));
                ClientPlayNetworking.send(new N1Packet(60));
            } catch (Throwable t) {
                failure = t.toString();
            }
            waited = 0;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (waited < 0) return;
            boolean answered = raw >= 0 && typed >= 0;
            if (!answered && ++waited < TIMEOUT_TICKS) return;
            waited = -1;
            TestPhases.run("client-echo", List.of(new Test38ClientChannelEcho()));
        });
    }

    @Override
    public String description() {
        return "old raw and typed packets travel from the client to the server and back";
    }

    @Override
    public TestResult run() {
        if (failure != null) return TestResult.fail(failure);
        if (raw != 51) return TestResult.fail("the raw answer was " + raw + ", not 51");
        if (typed != 61) return TestResult.fail("the typed answer was " + typed + ", not 61");
        return TestResult.success();
    }
}
