// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.network.protocol.game;

public record ServerboundAttackPacket(int entityId) implements net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ServerGamePacketListener> {
}
