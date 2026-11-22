// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.RandomSource;

public interface ParticleProvider<T extends ParticleOptions> {
    Particle createParticle(T options, ClientLevel level, double x, double y, double z, double xd, double yd, double zd, RandomSource random);
}
