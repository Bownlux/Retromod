// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.phys;

public class Vec3 {
    // Not constants: a field read must stay a field read in the rebuilt classes.
    public final double x;
    public final double y;
    public final double z;
    public static final Vec3 ZERO = null;
    public Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    public Vec3 add(Vec3 other) { throw new UnsupportedOperationException(); }
    public Vec3 multiply(double x, double y, double z) { throw new UnsupportedOperationException(); }
    public Vec3 scale(double factor) { throw new UnsupportedOperationException(); }
}
