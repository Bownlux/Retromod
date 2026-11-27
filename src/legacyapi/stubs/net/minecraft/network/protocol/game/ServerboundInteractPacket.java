// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.network.protocol.game;

public record ServerboundInteractPacket(int entityId, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.Vec3 location,
        boolean usingSecondaryAction) implements net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ServerGamePacketListener> {
}
