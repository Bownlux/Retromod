package net.fabricmc.fabric.api.networking.v1;

import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public interface PayloadTypeRegistry<B> {
    <T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super B, T> register(
            CustomPacketPayload.Type<T> type, StreamCodec<? super B, T> codec);

    static PayloadTypeRegistry<Object> clientboundPlay() { return PayloadTypeRegistryImpl.CLIENTBOUND_PLAY; }
    static PayloadTypeRegistry<Object> serverboundPlay() { return PayloadTypeRegistryImpl.SERVERBOUND_PLAY; }
    static PayloadTypeRegistry<Object> clientboundConfiguration() { return PayloadTypeRegistryImpl.CLIENTBOUND_CONFIGURATION; }
    static PayloadTypeRegistry<Object> serverboundConfiguration() { return PayloadTypeRegistryImpl.SERVERBOUND_CONFIGURATION; }
}
