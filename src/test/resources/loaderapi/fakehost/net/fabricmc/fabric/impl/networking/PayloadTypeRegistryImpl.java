package net.fabricmc.fabric.impl.networking;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/** Throws on a second registration for one id, as Fabric API 26.3 does. */
public final class PayloadTypeRegistryImpl implements PayloadTypeRegistry<Object> {
    public static final PayloadTypeRegistryImpl CLIENTBOUND_PLAY = new PayloadTypeRegistryImpl();
    public static final PayloadTypeRegistryImpl SERVERBOUND_PLAY = new PayloadTypeRegistryImpl();
    public static final PayloadTypeRegistryImpl CLIENTBOUND_CONFIGURATION = new PayloadTypeRegistryImpl();
    public static final PayloadTypeRegistryImpl SERVERBOUND_CONFIGURATION = new PayloadTypeRegistryImpl();

    private final Map<Identifier, CustomPacketPayload.TypeAndCodec<Object, ?>> types = new HashMap<>();

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super Object, T> register(
            CustomPacketPayload.Type<T> type, StreamCodec<? super Object, T> codec) {
        if (types.containsKey(type.id())) {
            throw new IllegalArgumentException("Packet type " + type + " is already registered!");
        }
        CustomPacketPayload.TypeAndCodec entry = new CustomPacketPayload.TypeAndCodec(type, codec.cast());
        types.put(type.id(), entry);
        return entry;
    }

    public CustomPacketPayload.TypeAndCodec<Object, ?> get(Identifier id) { return types.get(id); }

    public static void resetAll() {
        CLIENTBOUND_PLAY.types.clear();
        SERVERBOUND_PLAY.types.clear();
        CLIENTBOUND_CONFIGURATION.types.clear();
        SERVERBOUND_CONFIGURATION.types.clear();
    }
}
