// Compile-only stub of a NeoForge 1.21.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.network.handling;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public interface IPayloadHandler<T extends CustomPacketPayload> {
    void handle(T payload, IPayloadContext context);
}
