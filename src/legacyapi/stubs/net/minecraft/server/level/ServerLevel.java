// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.level;

import net.minecraft.world.level.Level;

public class ServerLevel extends Level {
    public <T extends net.minecraft.core.particles.ParticleOptions> int sendParticles(T particle, double x, double y, double z, int count, double dx, double dy, double dz, double speed) { throw new UnsupportedOperationException(); }
    public net.minecraft.world.level.gamerules.GameRules getGameRules() { throw new UnsupportedOperationException(); }
}
