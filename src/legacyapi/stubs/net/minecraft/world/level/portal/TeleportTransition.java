// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.portal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

// A record on the host.
public final class TeleportTransition {
    public static final PostTeleportTransition DO_NOTHING = null;
    public TeleportTransition(ServerLevel newLevel, Vec3 position, Vec3 deltaMovement, float yRot, float xRot,
            PostTeleportTransition postTeleportTransition) {}
    public Vec3 position() { throw new UnsupportedOperationException(); }
    public Vec3 deltaMovement() { throw new UnsupportedOperationException(); }
    public float yRot() { throw new UnsupportedOperationException(); }
    public float xRot() { throw new UnsupportedOperationException(); }
    public PostTeleportTransition postTeleportTransition() { throw new UnsupportedOperationException(); }

    public interface PostTeleportTransition {
    }
}
