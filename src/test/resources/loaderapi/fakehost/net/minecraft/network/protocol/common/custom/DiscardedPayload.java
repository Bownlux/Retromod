package net.minecraft.network.protocol.common.custom;

import net.minecraft.resources.Identifier;

/** What Minecraft reads an unknown custom payload as: its id and nothing else. */
public record DiscardedPayload(Identifier id) implements CustomPacketPayload {
    @Override
    public Type<DiscardedPayload> type() {
        return new Type<>(id);
    }
}
