// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.levelgen.synth;

import net.minecraft.util.Interval;

public interface Noise {
    Interval range();

    float get(double x, double y, double z);
}
