/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.network;

import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.Objects;

/** NeoForge 1.21.1's handler pair for a payload that travels both ways, which NeoForge 1.21.7 removed. */
public final class DirectionalPayloadHandler<T extends CustomPacketPayload> implements IPayloadHandler<T> {

    private final IPayloadHandler<T> clientSide;
    private final IPayloadHandler<T> serverSide;

    public DirectionalPayloadHandler(IPayloadHandler<T> clientSide, IPayloadHandler<T> serverSide) {
        this.clientSide = clientSide;
        this.serverSide = serverSide;
    }

    @Override
    public void handle(T payload, IPayloadContext context) {
        // The client handles what the server sends it, and the server what a client sends.
        PacketFlow flow = context.flow();
        if (flow == PacketFlow.CLIENTBOUND) {
            clientSide.handle(payload, context);
        } else if (flow == PacketFlow.SERVERBOUND) {
            serverSide.handle(payload, context);
        }
    }

    public IPayloadHandler<T> clientSide() {
        return clientSide;
    }

    public IPayloadHandler<T> serverSide() {
        return serverSide;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DirectionalPayloadHandler<?> that
                && Objects.equals(clientSide, that.clientSide) && Objects.equals(serverSide, that.serverSide);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientSide, serverSide);
    }

    @Override
    public String toString() {
        return "DirectionalPayloadHandler[clientSide=" + clientSide + ", serverSide=" + serverSide + "]";
    }
}
