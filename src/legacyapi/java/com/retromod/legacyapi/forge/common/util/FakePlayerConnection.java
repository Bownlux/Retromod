/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.common.util;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** A fake player's connection: its channel never opens, and it drops every packet and disconnect. */
public class FakePlayerConnection extends ServerGamePacketListenerImpl {

    public FakePlayerConnection(MinecraftServer server, ServerPlayer player) {
        super(server, new Connection(PacketFlow.CLIENTBOUND), player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false));
    }

    @Override
    public void send(Packet<?> packet) {}

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {}

    @Override
    public void disconnect(Component reason) {}

    @Override
    public void disconnect(DisconnectionDetails details) {}
}
