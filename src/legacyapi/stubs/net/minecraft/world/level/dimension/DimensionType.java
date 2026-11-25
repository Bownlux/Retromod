// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.dimension;

public final class DimensionType {
    public static final float[] MOON_BRIGHTNESS_PER_PHASE = null;
    public boolean hasCeiling() { throw new UnsupportedOperationException(); }
    public boolean hasSkyLight() { throw new UnsupportedOperationException(); }
    public net.minecraft.world.attribute.EnvironmentAttributeMap attributes() { throw new UnsupportedOperationException(); }
    public Skybox skybox() { throw new UnsupportedOperationException(); }
    public net.minecraft.world.level.CardinalLighting.Type cardinalLightType() { throw new UnsupportedOperationException(); }

    public enum Skybox { NONE, OVERWORLD, END }
}
