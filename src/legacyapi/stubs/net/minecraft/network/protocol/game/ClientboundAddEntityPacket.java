// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.network.protocol.game;

public class ClientboundAddEntityPacket implements net.minecraft.network.protocol.Packet<Object> {
    public ClientboundAddEntityPacket(int id, java.util.UUID uuid, double x, double y, double z, float xRot, float yRot,
            net.minecraft.world.entity.EntityType<?> type, int data, net.minecraft.world.phys.Vec3 movement, double yHeadRot) {
        throw new UnsupportedOperationException();
    }
}
