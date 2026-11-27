/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.network;

import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.Entity;

/** Buffer and packet calls in their 1.20.1 shapes, writing the same data the old forms did. */
public final class LegacyNetworkCalls {

    private LegacyNetworkCalls() {}

    /** {@code buf.readById(map)}: the value with the id read, or null for an id the map lacks. */
    public static <T> T readById(FriendlyByteBuf buf, IdMap<T> map) {
        return buf.readById(map::byId);
    }

    /** {@code buf.writeId(map, value)}: the value's id, which must be in the map. */
    public static <T> void writeId(FriendlyByteBuf buf, IdMap<T> map, T value) {
        buf.writeVarInt(map.getIdOrThrow(value));
    }

    /** {@code new ClientboundAddEntityPacket(entity)}: the entity where it stands, with no extra data. */
    public static ClientboundAddEntityPacket addEntity(Entity entity) {
        return addEntity(entity, 0);
    }

    /** {@code new ClientboundAddEntityPacket(entity, data)}: the entity's exact position, rotation and motion. */
    public static ClientboundAddEntityPacket addEntity(Entity entity, int data) {
        return new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(), entity.getZ(),
                entity.getXRot(), entity.getYRot(), entity.getType(), data, entity.getDeltaMovement(), entity.getYHeadRot());
    }
}
